package jp.aegif.nemaki;

import org.springframework.context.annotation.*;

import jp.aegif.nemaki.plugin.action.JavaBackedAction;
import jp.aegif.nemaki.util.action.NemakiActionPlugin;
import org.springframework.plugin.core.config.EnablePluginRegistries;

@EnablePluginRegistries(JavaBackedAction.class)
@ImportResource("classpath*:META-INF/plugins.xml")
@Configuration
public class AppConfig {
    @Bean
    NemakiActionPlugin NemakiAction() {
        return new NemakiActionPlugin();
    }
}