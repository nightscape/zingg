# Heterogeneous N-way record linkage

This example links person records that live in **three different systems**, where
the *same* information sits in **different columns** and is **formatted
differently** in each system. It exercises the zscala "align then link" path:
each source is first mapped onto a shared logical schema, then linked across
sources.

## The data

Six people appear across three CSV sources. No source has all six, and the
column names and value formats differ everywhere.

| Logical field | `crm.csv`       | `billing.csv`   | `support.csv`   |
|---------------|-----------------|-----------------|-----------------|
| `name`        | `full_name`     | `customer_name` | `contact`       |
| `email`       | `email_address` | `mail`          | `email`         |
| `phone`       | `phone`         | `telephone`     | `phone_number`  |
| `city`        | `city`          | `town`          | `location`      |

Formatting also differs and is normalised before comparison:

- **email** — `ALICE.JOHNSON@EXAMPLE.COM` / `alice.johnson@example.com` /
  `Alice.Johnson@example.com` → normalised with `lower`.
- **phone** — `555-100-2001` / `(555) 100-2001` / `5551002001` → normalised with
  `digits` (strips everything but digits).

`Emma Brown` even appears as `Emma Browne` in support — the fuzzy name match plus
the strong normalised email/phone keys still link her.

## The config

`config.json` turns on linkage and describes, per source, how its physical
columns map onto the logical fields and which normaliser to apply:

```json
{
  "link": true,
  "fieldDefinition": [
    { "fieldName": "name",  "matchType": "fuzzy" },
    { "fieldName": "email", "matchType": "email" },
    { "fieldName": "phone", "matchType": "fuzzy" },
    { "fieldName": "city",  "matchType": "fuzzy" }
  ],
  "data": [
    { "name": "crm", "format": "csv",
      "props": { "path": "examples/heterogeneous-link/crm.csv", "header": true },
      "fieldMapping":     { "name": "full_name", "email": "email_address", "phone": "phone", "city": "city" },
      "fieldNormalizers": { "email": "lower", "phone": "digits" } }
    /* ... billing, support ... */
  ]
}
```

- `link: true` keeps each source's records distinct and only generates
  **cross-source** candidate pairs (records from the same system are never paired
  with each other).
- `fieldDefinition` is the **logical schema** — the fields the model reasons
  about. The `fieldName` here is the logical name, not a physical column.
- `fieldMapping` (per source) maps `logicalField → physicalColumn`. A logical
  field with no entry falls back to identity (same name), so you only list the
  columns that differ.
- `fieldNormalizers` (per source) pre-normalises a logical field's values.
  Built-ins: `lower`, `upper`, `trim`, `digits`, `alnum`.

Available `matchType`s: `exact`, `fuzzy`, `email`, `numeric`, `text` (long text),
`cve` (CVE identifiers).

## Running it

Build the CLI jar and run the phases from the repository root:

```bash
./mill zscala-app.assembly
JAR=out/zscala-app/assembly.dest/out.jar

# 1. Generate candidate (cross-source) pairs to label
java -cp "$JAR" zingg.zs.Main --phase findTrainingData \
  --conf examples/heterogeneous-link/config.json --zinggDir models --modelId 200

# 2. Label them interactively (y / n / s / q)
java -cp "$JAR" zingg.zs.Main --phase label \
  --conf examples/heterogeneous-link/config.json --zinggDir models --modelId 200

# 3. Train the model (also persists the learned blocking tree)
java -cp "$JAR" zingg.zs.Main --phase train \
  --conf examples/heterogeneous-link/config.json --zinggDir models --modelId 200

# 4. Link across all three sources
java -cp "$JAR" zingg.zs.Main --phase link \
  --conf examples/heterogeneous-link/config.json --zinggDir models --modelId 200
```

The linked output (under `/tmp/zinggHeterogeneousLink`) carries a `z_source`
column (which system each record came from) and a `z_cluster` id shared by all
records that resolve to the same person — so a single person's CRM, billing, and
support rows end up with the same `z_cluster`.

`HeterogeneousLinkExampleTest` runs this exact config and data end-to-end (with an
automated oracle in place of the interactive labeller) and asserts that all six
people are linked across the sources.
