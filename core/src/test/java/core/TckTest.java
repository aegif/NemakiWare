package core;

import java.io.File;

import org.apache.chemistry.opencmis.tck.runner.ConsoleRunner;
import org.junit.Test;

public class TckTest {
	@Test
	public void runTck() throws Exception{
		
		File groups = new File(TckTest.class.getClassLoader().getResource("cmis-tck-groups.txt").getFile());
		File parameters = new File(TckTest.class.getClassLoader().getResource("cmis-tck-parameters.properties").getFile());
		System.out.println(groups.getAbsolutePath());

		String[] args = {parameters.getAbsolutePath(), groups.getAbsolutePath(),};
		ConsoleRunner.main(args);
	}
}
