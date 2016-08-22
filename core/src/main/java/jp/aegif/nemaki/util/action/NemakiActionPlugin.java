package jp.aegif.nemaki.util.action;

import org.apache.chemistry.opencmis.commons.data.ObjectData;
import org.springframework.beans.factory.annotation.Autowired;

import jp.aegif.nemaki.plugin.action.JavaBackedAction;

import java.util.List;
import java.util.Map;

public class NemakiActionPlugin {

    @Autowired
    Map<String, JavaBackedAction> pluginMap;
}


