package no.uio.ifi.ltp;

import no.uio.ifi.tc.TSDFileAPIClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@EnableCaching
@SpringBootApplication
public class LocalEGATSDProxyApplication extends WebSecurityConfigurerAdapter {

    @Value("${tsd.host}")
    private String tsdHost;

    @Value("${tsd.access-key}")
    private String tsdAccessKey;

    @Value("${tsd.key-store}")
    private String tsdKeyStore;

    @Value("${tsd.key-store-password}")
    private String tsdKeyStorePassword;

    @Value("${spring.security.oauth2.client.registration.elixir-aai.redirect-uri}")
    private String redirectURI;

    public static void main(String[] args) {
        SpringApplication.run(LocalEGATSDProxyApplication.class, args);
    }

    @Bean
    public TSDFileAPIClient tsdFileAPIClient() {
        return new TSDFileAPIClient.Builder()
                .host(tsdHost)
                .clientCertificateStore(tsdKeyStore, tsdKeyStorePassword)
                .accessKey(tsdAccessKey)
                .build();
    }

    @Bean
    public String secret() {
        return UUID.randomUUID().toString();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Override
    protected void configure(HttpSecurity http) throws Exception {
        String baseURI = redirectURI.substring(redirectURI.lastIndexOf("/"));
        http.csrf().disable()
                .authorizeRequests()
                .antMatchers("/").authenticated()
                .antMatchers("/**").permitAll()
                .and()
                .oauth2Login()
                .defaultSuccessUrl("/")
                .redirectionEndpoint().baseUri(baseURI);
    }

}
