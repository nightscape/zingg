package zingg.spark.core.executor;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataType;

import zingg.common.client.ClientOptions;
import zingg.common.client.ZinggClientException;
import zingg.common.client.arguments.model.IZArgs;
import zingg.common.client.options.ZinggOptions;
import zingg.common.client.util.DFObjectUtil;
import zingg.common.client.util.IWithSession;
import zingg.common.client.util.WithSession;
import zingg.common.core.executor.AILabeller;
import zingg.spark.client.util.SparkDFObjectUtil;
import zingg.spark.core.context.ZinggSparkContext;
import zingg.spark.core.preprocess.ISparkPreprocMapSupplier;

/**
 * Spark-specific implementation of {@link AILabeller}.
 *
 * <p>Usage:
 * <pre>
 *   export ZINGG_AI_ENDPOINT=http://localhost:11434/v1/chat/completions
 *   export ZINGG_AI_MODEL=llama3
 *   zingg.sh --phase aiLabel --conf config.json
 * </pre>
 */
public class SparkAILabeller extends AILabeller<SparkSession, Dataset<Row>, Row, Column, DataType>
		implements ISparkPreprocMapSupplier {

	private static final long serialVersionUID = 1L;
	public static final String name = "zingg.spark.core.executor.SparkAILabeller";
	public static final Log LOG = LogFactory.getLog(SparkAILabeller.class);

	public SparkAILabeller() {
		this(new ZinggSparkContext());
	}

	public SparkAILabeller(ZinggSparkContext sparkContext) {
		setZinggOption(ZinggOptions.LABEL);
		setContext(sparkContext);
		setName(name);
	}

	@Override
	public void init(IZArgs args, SparkSession s, ClientOptions options) throws ZinggClientException {
		super.init(args, s, options);
		getContext().init(s);
	}

	@Override
	protected DFObjectUtil<SparkSession, Dataset<Row>, Row, Column> getDfObjectUtil() {
		IWithSession<SparkSession> iWithSession = new WithSession<>();
		iWithSession.setSession(getContext().getSession());
		return new SparkDFObjectUtil(iWithSession);
	}
}
