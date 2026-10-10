package org.openelisglobal.config;

import org.openelisglobal.internationalization.MessageUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration
public class InternationalizationConfig {

    @Autowired
    RequestMappingHandlerMapping requestMappingHandlerMapping;

    /**
     * A deployment's own wording, layered over the bundled messages per key:
     * message_en.properties etc. in this directory (UTF-8). Keys it doesn't name
     * keep the bundled text, and without the directory nothing changes. The
     * backend counterpart of the frontend's /translation/&lt;locale&gt;.json
     * overrides, for text the server renders (reports, statuses, menus).
     */
    static final String DEPLOYMENT_MESSAGES = "file:/var/lib/openelis-global/translation/message";

    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messageSource = new ReloadableResourceBundleMessageSource();
        // the first basename that has a key wins
        messageSource.setBasenames(DEPLOYMENT_MESSAGES, "classpath:/languages/message");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(true);
        MessageUtil.setMessageSource(messageSource);
        return messageSource;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor localeChangeInterceptor = new LocaleChangeInterceptor();
        localeChangeInterceptor.setParamName("lang");
        return localeChangeInterceptor;
    }
}
