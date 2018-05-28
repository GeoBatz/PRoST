package loader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Encoders;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;

/**
 * Class that constructs complex property table. It operates over set of RDF
 * triples, collects and transforms information about them into a table. If we
 * have a list of predicates/properties p1, ... , pN, then the scheme of the
 * table is (s: STRING, p1: LIST<STRING> OR STRING, ..., pN: LIST<STRING> OR
 * STRING). Column s contains subjects. For each subject , there is only one row
 * in the table. Each predicate can be of complex or simple type. If a predicate
 * is of simple type means that there is no subject which has more than one
 * triple containing this property/predicate. Then the predicate column is of
 * type STRING. Otherwise, if a predicate is of complex type which means that
 * there exists at least one subject which has more than one triple containing
 * this property/predicate. Then the predicate column is of type LIST<STRING>.
 *
 * @author Matteo Cossu
 * @author Victor Anthony Arrascue Ayala
 */
public class PropertyTableLoader extends Loader {

	protected String hdfs_input_directory;

	private String tablename_properties = "properties";
	
	private String name_tripletable = "tripletable_fixed";

	/**
	 * Separator used internally to distinguish two values in the same string
	 */
	public String columns_separator = "\\$%";

	protected String output_db_name;
	protected static final String output_tablename = "property_table";

	public PropertyTableLoader(String hdfs_input_directory, String database_name, SparkSession spark) {
		super(hdfs_input_directory, database_name, spark);
	}

	public void load() {
		logger.info("PHASE 2: creating the property table...");

		buildProperties();

		// collect information for all properties
		List<Row> props = spark.sql(String.format("SELECT * FROM %s", tablename_properties)).collectAsList();
		String[] allProperties = new String[props.size()];
		Boolean[] isMultivaluedProperty = new Boolean[props.size()];

		for (int i = 0; i < props.size(); i++) {
			allProperties[i] = props.get(i).getString(0);
			isMultivaluedProperty[i] = props.get(i).getInt(1) == 1;
		}
	
		//We create a map with the properties and the boolean.
		Map<String, Boolean> propertiesMultivaluesMap = new HashMap<String, Boolean>();
		for (int i = 0; i < allProperties.length; i++) {
			String property = allProperties[i];
			Boolean multivalued = isMultivaluedProperty[i];
			propertiesMultivaluesMap.put(property, multivalued);
		}

		Map<String, Boolean> fixedPropertiesMultivaluesMap 
			= handleCaseInsPred(propertiesMultivaluesMap);
		
		List<String> allPropertiesList = new ArrayList<String>();
		List<Boolean> isMultivaluedPropertyList = new ArrayList<Boolean>();
		allPropertiesList.addAll(fixedPropertiesMultivaluesMap.keySet());
		
		for (int i = 0; i < allPropertiesList.size(); i++) {
			String property = allPropertiesList.get(i);
			isMultivaluedPropertyList.add(fixedPropertiesMultivaluesMap.get(property));
		}
		
		logger.info("All properties as array: " + allPropertiesList);
		logger.info("Multi-values flag as array: " + isMultivaluedPropertyList);
		
		allProperties = allPropertiesList.toArray(new String[allPropertiesList.size()]);
		this.properties_names = allProperties;
		isMultivaluedProperty = isMultivaluedPropertyList.toArray(new Boolean[allPropertiesList.size()]);

		// create multivalued property table
		buildMultivaluedPropertyTable(allProperties, isMultivaluedProperty);
	}

	/**
     * This method handles the problem when two predicate are the same in a 
     * case-insensitive context but different in a case-sensitve one. For instance: 
     * <http://example.org/somename> and <http://example.org/someName>.
     *  Since Hive is case insensitive the problem will be solved removing one of 
     *  the entries from the list of predicates.
     */
    public Map<String, Boolean> handleCaseInsPred(Map<String, Boolean> propertiesMultivaluesMap) {
    		Set<String> seenPredicates = new HashSet<String>();
    		Set<String> originalRemovedPredicates = new HashSet<String>(); 
    		
    		Iterator it = propertiesMultivaluesMap.keySet().iterator();
    	    while (it.hasNext()) {
    	    	String predicate = (String)it.next();
    	    	if (seenPredicates.contains(predicate.toLowerCase()))
    	    		originalRemovedPredicates.add(predicate); 
    	    	else
    	    		seenPredicates.add(predicate.toLowerCase());    		
    	    }
    	    
    	    for (String predicateToBeRemoved:originalRemovedPredicates )
    	    	propertiesMultivaluesMap.remove(predicateToBeRemoved);
    	    
    	    if (originalRemovedPredicates.size() > 0)
    	    	logger.info("The following predicates had to be removed from the list of predicates "
    	    			+ "(it is case-insensitive equal to another predicate): " + originalRemovedPredicates);		
    	    return propertiesMultivaluesMap;
    }

	public void buildProperties() {
		// return rows of format <predicate, is_complex>
		// is_complex can be 1 or 0
		// 1 for multivalued predicate, 0 for single predicate

		// select the properties that are multivalued
		Dataset<Row> multivaluedProperties = spark.sql(String.format(
				"SELECT DISTINCT(%1$s) AS %1$s FROM "
						+ "(SELECT %2$s, %1$s, COUNT(*) AS rc FROM %3$s GROUP BY %2$s, %1$s HAVING rc > 1) AS grouped",
				column_name_predicate, column_name_subject, name_tripletable));

		List<String> multivaluedPropertiesList = multivaluedProperties.as(Encoders.STRING()).collectAsList();
		if (multivaluedPropertiesList.size() > 0) {
			logger.info("Multivalued Properties found:");
			for (String property : multivaluedPropertiesList) {
				logger.info(property);
			}
		}

		// select all the properties
		Dataset<Row> allProperties = spark
				.sql(String.format("SELECT DISTINCT(%1$s) AS %1$s FROM %2$s", column_name_predicate, name_tripletable));

		List<String> allPropertiesList = allProperties.as(Encoders.STRING()).collectAsList();
		if (allPropertiesList.size() > 0) {
			logger.info("All Properties found:");
			for (String property : allPropertiesList) {
				logger.info(property);
			}
		}

		// select the properties that are not multivalued
		Dataset<Row> singledValueProperties = allProperties.except(multivaluedProperties);

		List<String> singledValuePropertiesList = singledValueProperties.as(Encoders.STRING()).collectAsList();
		if (singledValuePropertiesList.size() > 0) {
			logger.info("Single-valued Properties found:");
			for (String property : singledValuePropertiesList) {
				logger.info(property);
			}
		}

		// combine them
		Dataset<Row> combinedProperties = singledValueProperties.selectExpr(column_name_predicate, "0 AS is_complex")
				.union(multivaluedProperties.selectExpr(column_name_predicate, "1 AS is_complex"));

		List combinedPropertiesList = combinedProperties.as(Encoders.tuple(Encoders.STRING(), Encoders.INT()))
				.collectAsList();
		if (combinedPropertiesList.size() > 0) {
			logger.info("All Properties with a flag to specify whether they are multi-valued:");
			for (Object property : combinedPropertiesList) {
				logger.info(property);
			}
		}

		// remove '<' and '>', convert the characters
		Dataset<Row> cleanedProperties = combinedProperties.withColumn("p",
				functions.regexp_replace(functions.translate(combinedProperties.col("p"), "<>", ""), "[[^\\w]+]", "_"));

		List cleanedPropertiesList = cleanedProperties.as(Encoders.tuple(Encoders.STRING(), Encoders.INT()))
				.collectAsList();
		if (cleanedPropertiesList.size() > 0) {
			logger.info("Clean Properties (stored):");
			for (Object property : cleanedPropertiesList) {
				logger.info(property);
			}
		}

		// write the result
		cleanedProperties.write().mode(SaveMode.Overwrite).saveAsTable("properties");
	}

	/**
	 * Create the final property table, allProperties contains the list of all
	 * possible properties isMultivaluedProperty contains (in the same order
	 * used by allProperties) the boolean value that indicates if that property
	 * is multi-valued or not.
	 */
	public void buildMultivaluedPropertyTable(String[] allProperties, Boolean[] isMultivaluedProperty) {
		logger.info("Building the complete property table.");

		// create a new aggregation environment
		PropertiesAggregateFunction aggregator = new PropertiesAggregateFunction(allProperties, columns_separator);

		String predicateObjectColumn = "po";
		String groupColumn = "group";

		// get the compressed table
		Dataset<Row> compressedTriples = spark.sql(String.format("SELECT %s, CONCAT(%s, '%s', %s) AS po FROM %s",
				column_name_subject, column_name_predicate, columns_separator, column_name_object, name_tripletable));

		// group by the subject and get all the data
		Dataset<Row> grouped = compressedTriples.groupBy(column_name_subject)
				.agg(aggregator.apply(compressedTriples.col(predicateObjectColumn)).alias(groupColumn));

		// build the query to extract the property from the array
		String[] selectProperties = new String[allProperties.length + 1];
		selectProperties[0] = column_name_subject;
		for (int i = 0; i < allProperties.length; i++) {

			// if property is a full URI, remove the < at the beginning end > at
			// the end
			String rawProperty = allProperties[i].startsWith("<") && allProperties[i].endsWith(">")
					? allProperties[i].substring(1, allProperties[i].length() - 1) : allProperties[i];
			// if is not a complex type, extract the value
			String newProperty = isMultivaluedProperty[i]
					? " " + groupColumn + "[" + String.valueOf(i) + "] AS " + getValidHiveName(rawProperty)
					: " " + groupColumn + "[" + String.valueOf(i) + "][0] AS " + getValidHiveName(rawProperty);
			selectProperties[i + 1] = newProperty;
		}

		List<String> allPropertiesList = Arrays.asList(selectProperties);
		logger.info("Columns of  Property Table: " + allPropertiesList);

		Dataset<Row> propertyTable = grouped.selectExpr(selectProperties);

		//List<Row> sampledRowsList = propertyTable.limit(10).collectAsList();
		//logger.info("First 10 rows sampled from the PROPERTY TABLE (or less if there are less): " + sampledRowsList);

		// write the final one, partitioned by subject
		propertyTable.write().mode(SaveMode.Overwrite).format(table_format).saveAsTable(output_tablename);
		logger.info("Created property table with name: " + output_tablename);

	}

}