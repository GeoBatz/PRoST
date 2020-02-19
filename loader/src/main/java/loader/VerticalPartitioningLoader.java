package loader;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import com.esotericsoftware.minlog.Log;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

/**
 * Build the VP, i.e. a table for each predicate.
 *
 * @author Matteo Cossu
 * @author Victor Anthony Arrascue Ayala
 *
 */
public class VerticalPartitioningLoader extends Loader {
	private final boolean computeStatistics;
	private final Map<String, String> predDictionary;
	private String dict_file_name;
	private Map<String, TableInfo> statistics;
	private String metadata_file_name;
	private boolean generateExtVP;
	private double threshold;

	public VerticalPartitioningLoader(final String hdfs_input_directory, final String database_name,
			final SparkSession spark, final boolean computeStatistics, final String statisticsfile,
			final String dictionaryfile, boolean generateExtVP, double thresholdExtVP) {
		super(hdfs_input_directory, database_name, spark);
		this.computeStatistics = computeStatistics;
		this.predDictionary = new HashMap<String, String>();
		this.statistics = new HashMap<String, TableInfo>();
		this.dict_file_name = dictionaryfile;
		this.metadata_file_name = statisticsfile;
		this.generateExtVP = generateExtVP;
		this.threshold = thresholdExtVP;
	}

	@Override
	public void load() {
		logger.info("PHASE 3: creating the VP tables...");

		if (properties_names == null) {
			properties_names = extractProperties();
		}
		try {
			generatePredicateDictionary();
		} catch (IOException e) {
			e.printStackTrace();
		}

		for (int i = 0; i < properties_names.length; i++) {
			final String property = this.predDictionary.get(properties_names[i]);
			final String queryDropVPTableFixed = String.format("DROP TABLE IF EXISTS %s", property);
			spark.sql(queryDropVPTableFixed);

			final String createVPTableFixed = String.format(
					"CREATE TABLE  IF NOT EXISTS  %1$s(%2$s STRING, %3$s STRING) STORED AS PARQUET", property,
					column_name_subject, column_name_object);
			// Commented code is partitioning by subject
			/*
			 * String createVPTableFixed = String.format(
			 * "CREATE TABLE  IF NOT EXISTS  %1$s(%3$s STRING) PARTITIONED BY (%2$s STRING) STORED AS PARQUET"
			 * , "vp_" + this.getValidHiveName(property), column_name_subject,
			 * column_name_object);
			 */
			spark.sql(createVPTableFixed);

			final String populateVPTable = String.format(
					"INSERT INTO TABLE %1$s " + "SELECT %2$s, %3$s " + "FROM %4$s WHERE %5$s = '%6$s' ", property,
					column_name_subject, column_name_object, name_tripletable, column_name_predicate,
					properties_names[i]);
			// Commented code is partitioning by subject
			/*
			 * String populateVPTable = String.format(
			 * "INSERT OVERWRITE TABLE %1$s PARTITION (%2$s) " + "SELECT %3$s, %2$s " +
			 * "FROM %4$s WHERE %5$s = '%6$s' ", "vp_" + this.getValidHiveName(property),
			 * column_name_subject, column_name_object, name_tripletable,
			 * column_name_predicate, property);
			 */
			spark.sql(populateVPTable);

			// calculate stats
			final Dataset<Row> table_VP = spark.sql("SELECT * FROM " + property);

			if (computeStatistics) {
				statistics.put(property, calculate_stats_table(table_VP, property));
			}

			logger.info("Created VP table for the property: " + property);
			final List<Row> sampledRowsList = table_VP.limit(3).collectAsList();
			logger.info("First 3 rows sampled (or less if there are less): " + sampledRowsList);
		}

		// save the stats in a file with the same name as the output database
		if (computeStatistics) {
			try {
				save_stats();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		// what happens if asWKT or hasGeometry does not exists?
		try {
			Dataset<Row> geometries = spark.sql("Select g.s as entity, ifnull(w.s, g.o) as geom, w.o as wkt from "
					+ predDictionary.get("http://www.opengis.net/ont/geosparql#asWKT") + " w full outer join "
					+ predDictionary.get("http://www.opengis.net/ont/geosparql#hasGeometry") + " g on g.o=w.s");
			geometries.write().saveAsTable("geometries");
			//put as stats for geometries table the hasGeometry stats
			statistics.put("geometries", statistics.get(predDictionary.get("http://www.opengis.net/ont/geosparql#hasGeometry")));
		} catch (Exception e) {
			logger.error("Could not create geometries table: " + e.getMessage());
		}

		logger.info("Vertical Partitioning completed. Loaded " + String.valueOf(properties_names.length) + " tables.");

		if (generateExtVP) {
			ExtVPCreator extvp = new ExtVPCreator(predDictionary, spark, threshold, statistics);
			extvp.createExtVP("SS");
			extvp.createExtVP("SO");
			extvp.createExtVP("OS");
			try {
				save_extvp_stats(extvp.getExtVPStats());
			} catch (Exception e) {
				logger.error("Could not save ExtVP statistics: " + e.getMessage());
			}
		}
		/*
		 * try { Loader.parseCSVDictionary(dict_file_name); } catch (IOException e) {
		 * e.printStackTrace(); }
		 */
	}

	/*
	 * calculate the statistics for a single table: size, number of distinct
	 * subjects and isComplex. It returns a protobuf object defined in
	 * ProtobufStats.proto
	 */
	private TableInfo calculate_stats_table(final Dataset<Row> table, final String tableName) {

		// calculate the stats
		final int table_size = (int) table.count();
		final int distinct_subjects = (int) table.select(column_name_subject).distinct().count();
		final int distinct_objects = (int) table.select(column_name_object).distinct().count();
		logger.info("table:" + tableName + " has " + table_size + " rows");
		TableInfo tblInfo = new TableInfo(table_size, distinct_subjects, distinct_objects);

		return tblInfo;
	}

	/*
	 * save the statistics in a serialized file
	 */
	private void save_stats() throws IllegalArgumentException, IOException {
		logger.info("Saving Statistics");
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		FSDataOutputStream out = fs.create(new Path(metadata_file_name));
		for (String tablename : statistics.keySet()) {
			TableInfo ti = statistics.get(tablename);
			String fmt = String.format("%1$s,%2$s,%3$s,%4$s\n", tablename, ti.getCountAll(), ti.getDistinctSubjects(),
					ti.getDistinctObjects());
			byte[] bytes = fmt.getBytes();
			out.write(fmt.getBytes(), 0, bytes.length);
		}
		out.close();

	}

	private void save_extvp_stats(StringBuffer sb) throws IllegalArgumentException, IOException {
		logger.info("Saving ExtVP Statistics");
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		FSDataOutputStream out = fs.create(new Path(metadata_file_name + ".ext"));
		out.write(sb.toString().getBytes());
		out.close();

	}

	private String[] extractProperties() {
		final List<Row> props = spark
				.sql(String.format("SELECT DISTINCT(%1$s) AS %1$s FROM %2$s", column_name_predicate, name_tripletable))
				.collectAsList();
		final String[] properties = new String[props.size()];

		for (int i = 0; i < props.size(); i++) {
			properties[i] = props.get(i).getString(0);
		}

		final List<String> propertiesList = Arrays.asList(properties);
		logger.info("Number of distinct predicates found: " + propertiesList.size());
		final String[] cleanedProperties = handleCaseInsPred(properties);
		final List<String> cleanedPropertiesList = Arrays.asList(cleanedProperties);
		logger.info("Final list of predicates: " + cleanedPropertiesList);
		logger.info("Final number of distinct predicates: " + cleanedPropertiesList.size());
		return cleanedProperties;
	}

	private String[] handleCaseInsPred(final String[] properties) {
		final Set<String> seenPredicates = new HashSet<>();
		final Set<String> originalRemovedPredicates = new HashSet<>();

		final Set<String> propertiesSet = new HashSet<>(Arrays.asList(properties));

		final Iterator<String> it = propertiesSet.iterator();
		while (it.hasNext()) {
			final String predicate = it.next();
			if (seenPredicates.contains(predicate.toLowerCase())) {
				originalRemovedPredicates.add(predicate);
			} else {
				seenPredicates.add(predicate.toLowerCase());
			}
		}

		for (final String predicateToBeRemoved : originalRemovedPredicates) {
			propertiesSet.remove(predicateToBeRemoved);
		}

		if (originalRemovedPredicates.size() > 0) {
			logger.info("The following predicates had to be removed from the list of predicates "
					+ "(it is case-insensitive equal to another predicate): " + originalRemovedPredicates);
		}
		final String[] cleanedProperties = propertiesSet.toArray(new String[propertiesSet.size()]);
		return cleanedProperties;
	}

	/**
	 * Checks if there is at least one property that uses prefixes.
	 */
	private boolean arePrefixesUsed() {
		for (final String property : properties_names) {
			if (property.contains(":")) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Additions by D Halatsis Create a csv file that contains predicates and their
	 * new names
	 */
	private void generatePredicateDictionary() throws IOException {
		if (properties_names == null) {
			properties_names = extractProperties();
		}
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		FSDataOutputStream out = fs.create(new Path(dict_file_name));

		for (int i = 0; i < properties_names.length; i++) {
			predDictionary.put(properties_names[i], "prop" + i);
			String fmt = String.format("%1$s,prop%2$s\n", properties_names[i], i + "");
			byte[] bytes = fmt.getBytes();
			out.write(fmt.getBytes(), 0, bytes.length);
		}
		out.close();
	}

}
