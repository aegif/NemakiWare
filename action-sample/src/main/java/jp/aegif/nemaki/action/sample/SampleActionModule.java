package jp.aegif.nemaki.action.sample;

import com.google.inject.Binder;
import com.google.inject.multibindings.Multibinder;

import jp.aegif.nemaki.plugin.action.JavaBackedActionModule;
import jp.aegif.nemaki.plugin.action.JavaBackedUIAction;


public class SampleActionModule implements JavaBackedActionModule {

	@Override
	public void configure(Binder binder) {
		Multibinder<JavaBackedUIAction> mb = Multibinder.newSetBinder(binder, JavaBackedUIAction.class);
		mb.addBinding().to(SampleCmisObjectAction.class);
	}

}
