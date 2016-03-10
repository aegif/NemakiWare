package jp.aegif.nemaki.test.cmis;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Properties;

import org.apache.chemistry.opencmis.tck.runner.AbstractRunner;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

public class TestGroupBase extends AbstractRunner{

	static Properties filters = PropertyUtil.build(new File(TestGroupBase.class.getClassLoader().getResource("cmis-tck-filters.properties").getFile()));
	
	@Rule
	public TestName testName = new TestName();
	
	@Before
	public void beforeMethod() throws Exception{
		//loadParameters(parameters);
		filter(testName.getMethodName());
	}
	
	private void filter(String methodName){
		Assume.assumeTrue(Boolean.valueOf(filters.getProperty(methodName)));
	}
	
	private static class PropertyUtil{
		public static Properties build(File file){
			Properties properties = new Properties();
			try {
				properties.load(new FileInputStream(file));
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return properties;
		}
	}
}
