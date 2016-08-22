package jp.aegif.nemaki;

import org.springframework.context.annotation.*;

import jp.aegif.nemaki.plugin.action.JavaBackedAction;
import jp.aegif.nemaki.util.action.NemakiActionPlugin;

@ImportResource("classpath*:META-INF/plugins.xml")
@Configuration
public class AppConfig {
    @Bean
    NemakiActionPlugin NemakiAction() {
        return new NemakiActionPlugin();
    }
}