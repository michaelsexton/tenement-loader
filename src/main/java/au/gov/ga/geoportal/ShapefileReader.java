package au.gov.ga.geoportal;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.FeatureSource;
import org.geotools.data.Transaction;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.geotools.jdbc.JDBCDataStoreFactory;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.filter.Filter;

/**
 * @author michael
 *
 */
public class ShapefileReader {

	/**
	 * 
	 */
	public static String shapefileDir = "";

	/**
	 * 
	 */
	private File shapefile;

	/**
	 * @author michael
	 *
	 */
	public enum States {
		NSW, NT, QLD
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		for (States state : States.values()) {
			File shapefile = findShapefilePath(LocalDate.now(), state);
			if (shapefile.exists()) {

				ShapefileReader reader = new ShapefileReader(shapefile);
				try {
					reader.loadToOracle();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (CQLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

			}
		}
	}

	/**
	 * @param shapefile
	 */
	public ShapefileReader(File shapefile) {
		this.shapefile = shapefile;
	}

	private DataStore oracleDataStore() throws IOException {
		Map<Serializable, Object> params = oracleParams();
		DataStore oracleDataStore = DataStoreFinder.getDataStore(params);
		return oracleDataStore;
	}

	public void loadToOracle() throws IOException, CQLException {
		if (dataExists()) {
			System.out.println("Hello there is already data for " + shapefilePrefix());
		} else {

			SimpleFeatureType tenementsSchema = oracleDataStore().getSchema("TENEMENTS");

			SimpleFeatureSource oracleFeatureSource = oracleDataStore()
					.getFeatureSource(tenementsSchema.getName().getLocalPart());
			Transaction transaction = new DefaultTransaction("create");

			Map<String, Object> shapefileParamsMap = new HashMap<String, Object>();
			shapefileParamsMap.put("url", shapefile.toURI().toURL());

			DataStore shapefileDataStore = DataStoreFinder.getDataStore(shapefileParamsMap);
			String shapefileTypeName = shapefileDataStore.getTypeNames()[0];

			SimpleFeatureBuilder builder = new SimpleFeatureBuilder(tenementsSchema);

			FeatureSource<SimpleFeatureType, SimpleFeature> shapefileSource = shapefileDataStore
					.getFeatureSource(shapefileTypeName);
			Filter filter = Filter.INCLUDE;

			FeatureCollection<SimpleFeatureType, SimpleFeature> shapefileCollection = shapefileSource
					.getFeatures(filter);

			FeatureIterator<SimpleFeature> shapefileFeatures = shapefileCollection.features();

			SimpleFeatureStore oracleFeatureStore = (SimpleFeatureStore) oracleFeatureSource;

			oracleFeatureStore.setTransaction(transaction);

			while (shapefileFeatures.hasNext()) {
				SimpleFeature source = (SimpleFeature) shapefileFeatures.next();

				for (AttributeDescriptor attributeDescriptor : tenementsSchema.getAttributeDescriptors()) {
					String attribute = attributeDescriptor.getLocalName();
					builder.set(attribute.toUpperCase(), source.getAttribute(attribute));
					builder.set("GEOM", source.getDefaultGeometry());
					builder.set("STATE", state());
					builder.set("RECORDDATE", shapefileDate().format(DateTimeFormatter.ISO_DATE));
				}

				oracleFeatureStore.addFeatures(DataUtilities.collection(builder.buildFeature(null)));
			}
			transaction.commit();
			transaction.close();
		}

	}

	/**
	 * @param date
	 * @param state
	 * @return
	 */
	private static File findShapefilePath(LocalDate date, States state) {
		if (date.getYear() < 2000) {
			return new File("null");
		}
		int month = date.getMonthValue();
		String path = shapefileDir + state.toString().toLowerCase() + "_tenement_" + String.format("%02d", month);
		System.out.println(path);
		File shapefile = new File(path + ".shp");
		if (shapefile.exists()) {
			return shapefile;
		} else {
			path = path + "_" + String.format("%02d", date.getYear() % 100);
			System.out.println(path);
			shapefile = new File(path + ".shp");
			if (shapefile.exists()) {
				return shapefile;
			}
		}
		date = date.minusMonths(1);
		return findShapefilePath(date, state);
	}

	/**
	 * @return
	 * @throws IOException
	 * @throws CQLException
	 */
	private boolean dataExists() throws IOException, CQLException {

		String state = state();
		LocalDate date = shapefileDate();
		SimpleFeatureType tenementsSchema = oracleDataStore().getSchema("TENEMENTS");
		SimpleFeatureSource oracleFeatureSource = oracleDataStore()
				.getFeatureSource(tenementsSchema.getName().getLocalPart());

		// Filter stateFilter = CQL.toFilter("STATE = '" + state + "'");
		// Filter dateFilter = CQL.toFilter("RECORDDATE = '" +
		// date.format(formatter) + "'");
		
		Filter filter = CQL.toFilter("STATE = '" + state + "' AND RECORDDATE = '" + date + "'");
		// Query query = new Query(tenementsTypeName, filter);
		SimpleFeatureCollection features = oracleFeatureSource.getFeatures(filter);
		if (features.features().hasNext()) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * @return
	 */
	private LocalDate shapefileDate() {
		int year;
		// LocalDate date = LocalDate.now();
		String[] fileComponents = shapefileNameComponents();
		int month = Integer.parseInt(fileComponents[2]);
		if (fileComponents.length > 3) {
			year = Integer.parseInt(fileComponents[3]);
		} else {
			year = returnYearFromMonth(month);
		}
		YearMonth yearMonth = YearMonth.of(year, month);
		LocalDate date = yearMonth.atEndOfMonth();
		return date;
	}

	/**
	 * @param month
	 * @return
	 */
	private int returnYearFromMonth(int month) {
		LocalDate monthDate = LocalDate.now().withMonth(month);
		if (monthDate.isBefore(LocalDate.now())) {
			return monthDate.getYear();
		} else {
			return monthDate.getYear() - 1;
		}

	}

	/**
	 * @return Shapefile prefix from string based file name
	 */
	private String shapefilePrefix() {
		return shapefile.getName().split("\\.")[0];
	}

	/**
	 * @return Breaks the shapefile prefix file name into an array of components
	 *         that describe the state and month/year of the file
	 */
	private String[] shapefileNameComponents() {
		return shapefilePrefix().split("_");
	}

	private String state() {
		return shapefileNameComponents()[0].toUpperCase();
	}

	/**
	 * @return The parameters for connecting to Oracle.
	 */
	private static Map<Serializable, Object> oracleParams() {
		Map<Serializable, Object> parameters = new HashMap<Serializable, Object>();
		
		return parameters;
	}

}
