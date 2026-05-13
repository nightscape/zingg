package zingg.common.core.executor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import zingg.common.client.ZFrame;
import zingg.common.client.ZinggClientException;
import zingg.common.client.cols.ZidAndFieldDefSelector;
import zingg.common.client.options.ZinggOptions;
import zingg.common.client.util.ColName;
import zingg.common.client.util.ColValues;
import zingg.common.client.util.DFObjectUtil;
import zingg.common.core.util.LabelMatchType;

/**
 * AI-powered Labeller that uses an LLM via OpenAI-compatible API
 * to classify record pairs as MATCH, NO_MATCH, or NOT_SURE.
 *
 * Configuration via environment variables or system properties:
 *   ZINGG_AI_ENDPOINT  - API endpoint (default: http://localhost:11434/v1/chat/completions)
 *   ZINGG_AI_MODEL     - Model name (default: llama3)
 *   ZINGG_AI_KEY       - API key, leave empty for local endpoints like Ollama
 *   ZINGG_AI_TIMEOUT   - Request timeout in seconds (default: 30)
 */
public abstract class AILabeller<S, D, R, C, T> extends Labeller<S, D, R, C, T> {

	private static final long serialVersionUID = 1L;
	public static final Log LOG = LogFactory.getLog(AILabeller.class);

	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Token budget per call — reasoning models need extra for chain-of-thought */
	private static final int MAX_TOKENS = 100;

	protected String apiEndpoint;
	protected String modelName;
	protected String apiKey;
	protected int timeoutSeconds;

	public AILabeller() {
		setZinggOption(ZinggOptions.LABEL);
	}

	@Override
	public ZFrame<D, R, C> processRecordsCli(ZFrame<D, R, C> lines) throws ZinggClientException {
		LOG.info("=== AI Labelling Phase ===");
		readConfig();

		if (lines == null || lines.count() == 0) {
			LOG.info("No unmarked records. Run findTrainingData first to generate pairs.");
			return null;
		}

		ZFrame<D, R, C> clusterIdZFrame = getLabelDataViewHelper().getClusterIdsFrame(lines);
		List<R> clusterIDs = getLabelDataViewHelper().getClusterIds(clusterIdZFrame);
		int totalPairs = clusterIDs.size();
		LOG.info("Pairs to label: " + totalPairs);

		ZFrame<D, R, C> updatedRecords = null;

		ZidAndFieldDefSelector selector = new ZidAndFieldDefSelector(
				args.getFieldDefinition(), false, args.getShowConcise());

		getLabelDataViewHelper().printMarkedRecordsStat(
				getTrainingDataModel().getPositivePairsCount(),
				getTrainingDataModel().getNegativePairsCount(),
				getTrainingDataModel().getNotSurePairsCount(),
				getTrainingDataModel().getTotalCount());

		for (int index = 0; index < totalPairs; index++) {
			ZFrame<D, R, C> currentPair = getLabelDataViewHelper().getCurrentPair(
					lines, index, clusterIDs, clusterIdZFrame);

			double prediction = getLabelDataViewHelper().getPrediction(currentPair);
			double score = getLabelDataViewHelper().getScore(currentPair);

			LOG.info(String.format("\tAI labelling  : %d/%d pairs", index + 1, totalPairs));
			LOG.info(String.format("\tZingg predicts: %s (score: %.2f)",
					LabelMatchType.get(prediction).msg, score));

			int aiLabel = callAIForLabel(currentPair, selector);

			getTrainingDataModel().updateLabellerStat(aiLabel, INCREMENT);
			getLabelDataViewHelper().printMarkedRecordsStat(
					getTrainingDataModel().getPositivePairsCount(),
					getTrainingDataModel().getNegativePairsCount(),
					getTrainingDataModel().getNotSurePairsCount(),
					getTrainingDataModel().getTotalCount());

			updatedRecords = getTrainingDataModel().updateRecords(aiLabel, currentPair, updatedRecords);
		}

		LOG.info("=== AI Labelling Complete ===");
		return updatedRecords;
	}

	/**
	 * Read configuration from environment or system properties.
	 */
	protected void readConfig() {
		this.apiEndpoint = getConfig("ZINGG_AI_ENDPOINT", "http://localhost:11434/v1/chat/completions");
		this.modelName    = getConfig("ZINGG_AI_MODEL", "llama3");
		this.apiKey       = getConfig("ZINGG_AI_KEY", null);
		this.timeoutSeconds = Integer.parseInt(getConfig("ZINGG_AI_TIMEOUT", "30"));
		LOG.info("AI Labeller config — endpoint: " + apiEndpoint + ", model: " + modelName);
	}

	protected String getConfig(String key, String defaultValue) {
		String val = System.getenv(key);
		if (val != null && !val.isEmpty()) return val;
		val = System.getProperty(key);
		return (val != null && !val.isEmpty()) ? val : defaultValue;
	}

	/**
	 * Send the pair to the LLM and parse the response into a label.
	 * Falls back to NOT_SURE on any error.
	 */
	protected int callAIForLabel(ZFrame<D, R, C> pair, ZidAndFieldDefSelector selector)
			throws ZinggClientException {
		try {
			String prompt = buildPrompt(pair, selector);
			String responseBody = callLLM(prompt);
			return parseLabel(responseBody);
		} catch (Exception e) {
			LOG.warn("AI labelling error, defaulting to NOT_SURE: " + e.getMessage(), e);
			return ColValues.MATCH_TYPE_NOT_SURE;
		}
	}

	/**
	 * Build the prompt describing the record pair.
	 */
	protected String buildPrompt(ZFrame<D, R, C> pair, ZidAndFieldDefSelector selector)
			throws ZinggClientException {
		String recordsStr = formatRecordsForPrompt(pair);
		return "You are a record linkage expert. Determine if these two records refer to the same real-world entity.\n\n"
				+ "Respond with EXACTLY one digit and nothing else:\n"
				+ "  1 = SAME entity (MATCH)\n"
				+ "  0 = DIFFERENT entities (NO MATCH)\n"
				+ "  2 = NOT SURE / need more information\n\n"
				+ recordsStr
				+ "\n\nYour answer (0, 1, or 2):";
	}

	/**
	 * Format the record pair as a readable string for the LLM prompt.
	 * Each row in the pair represents one record (from source A or B).
	 * Override for framework-specific formatting, or use the default
	 * which iterates rows and columns.
	 */
	protected String formatRecordsForPrompt(ZFrame<D, R, C> pair) throws ZinggClientException {
		StringBuilder sb = new StringBuilder();
		List<R> rows = pair.collectAsList();
		String[] columns = pair.columns();

		for (int i = 0; i < rows.size(); i++) {
			R row = rows.get(i);
			String source = pair.getAsString(row, ColName.SOURCE_COL);
			sb.append("--- Record ").append(i + 1).append(" (source: ").append(source).append(") ---\n");
			for (String col : columns) {
				if (col.equals(ColName.SOURCE_COL) || col.equals(ColName.ID_COL)
						|| col.equals(ColName.CLUSTER_COLUMN) || col.equals(ColName.SCORE_COL)
						|| col.equals(ColName.PREDICTION_COL) || col.equals(ColName.MATCH_FLAG_COL)) {
					continue;
				}
				String val = pair.getAsString(row, col);
				if (val != null && !val.isEmpty()) {
					sb.append("  ").append(col).append(": ").append(val).append("\n");
				}
			}
			sb.append("\n");
		}
		return sb.toString();
	}

	/**
	 * Call the OpenAI-compatible chat completions endpoint.
	 * Uses java.net.http.HttpClient (Java 11+, zero extra dependencies).
	 */
	protected String callLLM(String prompt) throws Exception {
		ObjectNode body = MAPPER.createObjectNode();
		body.put("model", modelName);

		ArrayNode messages = body.putArray("messages");
		ObjectNode userMsg = messages.addObject();
		userMsg.put("role", "user");
		userMsg.put("content", prompt);

		body.put("temperature", 0.0);
		body.put("max_tokens", MAX_TOKENS);

		String json = MAPPER.writeValueAsString(body);

		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
				.uri(URI.create(apiEndpoint))
				.header("Content-Type", "application/json")
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.POST(HttpRequest.BodyPublishers.ofString(json));

		if (apiKey != null && !apiKey.isEmpty()) {
			requestBuilder.header("Authorization", "Bearer " + apiKey);
		}

		HttpRequest request = requestBuilder.build();
		HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			throw new RuntimeException("LLM API returned HTTP " + response.statusCode()
					+ ": " + response.body());
		}

		return response.body();
	}

	/**
	 * Parse the OpenAI chat completion response and extract the label digit.
	 * Handles both normal models (content field) and reasoning models
	 * (reasoning_content / reasoning fields from Qwen3, DeepSeek, etc.).
	 */
	protected int parseLabel(String responseBody) throws Exception {
		ObjectNode response = (ObjectNode) MAPPER.readTree(responseBody);
		ObjectNode message = (ObjectNode) response.get("choices").get(0).get("message");

		// Reasoning models like Qwen3/DeepSeek may put output in reasoning_content
		String content = getJsonTextField(message, "reasoning_content");
		if (content.isEmpty()) {
			content = getJsonTextField(message, "reasoning");
		}
		if (content.isEmpty()) {
			content = getJsonTextField(message, "content");
		}

		// Extract the first occurrence of 0, 1, or 2
		for (char c : content.toCharArray()) {
			if (c >= '0' && c <= '2') {
				int label = c - '0';
				String labelName = (label == 1) ? "MATCH" : (label == 0) ? "NO MATCH" : "NOT SURE";
				LOG.info("\tAI response => " + label + " (" + labelName + ")");
				return label;
			}
		}

		LOG.warn("Could not parse AI response, got: '" + content + "'. Defaulting to NOT SURE.");
		return ColValues.MATCH_TYPE_NOT_SURE;
	}

	private String getJsonTextField(ObjectNode parent, String fieldName) {
		var node = parent.get(fieldName);
		if (node == null || node.isNull()) return "";
		return node.asText().trim();
	}

	@Override
	protected abstract DFObjectUtil<S, D, R, C> getDfObjectUtil();
}
