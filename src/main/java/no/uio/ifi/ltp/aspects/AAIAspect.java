package no.uio.ifi.ltp.aspects;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import lombok.extern.slf4j.Slf4j;
import no.uio.ifi.clearinghouse.Clearinghouse;
import no.uio.ifi.clearinghouse.model.Visa;
import no.uio.ifi.clearinghouse.model.VisaType;
import no.uio.ifi.ltp.authentication.CEGACredentialsProvider;
import no.uio.ifi.ltp.dto.Credentials;
import org.apache.commons.codec.digest.Crypt;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.auth.AuthenticationException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.amqp.support.AmqpHeaders.USER_ID;

@Slf4j
@Aspect
@Order(1)
@Component
public class AAIAspect {

    @Value("${ga4gh.passport.openid-configuration-url}")
    private String openIDConfigurationURL;

    @Value("${ga4gh.passport.public-key-path}")
    private String passportPublicKeyPath;

    @Value("${ga4gh.visa.public-key-path}")
    private String visaPublicKeyPath;

    @Autowired
    protected HttpServletRequest request;

    @Autowired
    protected CEGACredentialsProvider cegaCredentialsProvider;

    @Around("execution(public * no.uio.ifi.ltp.rest.ProxyController.*(..))")
    public Object authenticate(ProceedingJoinPoint joinPoint) throws Throwable {
        Optional<String> optionalBearerAuth = getBearerAuth();
        if (optionalBearerAuth.isEmpty()) {
            log.info("Authentication attempt without Elixir AAI token provided");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Optional<String> optionalBasicAuth = getBasicAuth();
        if (optionalBasicAuth.isEmpty()) {
            log.info("Authentication attempt without EGA credentials provided");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String jwtToken = optionalBearerAuth.get().replace("Bearer ", "");
        try {
            DecodedJWT decodedJWT = JWT.decode(jwtToken);
            List<Visa> controlledAccessGrantsVisas = getVisas(jwtToken, decodedJWT);
            log.info("Elixir user {} authenticated and provided following valid GA4GH Visas: {}", decodedJWT.getSubject(), controlledAccessGrantsVisas);
            String[] usernameAndPassword = new String(Base64.getDecoder().decode(optionalBasicAuth.get().replace("Basic ", ""))).split(":");
            if (!cegaAuth(usernameAndPassword[0], usernameAndPassword[1])) {
                throw new AuthenticationException("EGA authentication failed");
            }
            log.info("EGA user {} authenticated", usernameAndPassword[0]);
            request.setAttribute(USER_ID, decodedJWT.getSubject());
            return joinPoint.proceed();
        } catch (Exception e) {
            log.info(e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    protected boolean cegaAuth(String username, String password) throws MalformedURLException, URISyntaxException {
        Credentials credentials = cegaCredentialsProvider.getCredentials(username);
        String hash = credentials.getPasswordHash();
        return StringUtils.startsWithIgnoreCase(hash, "$2")
                ? BCrypt.checkpw(password, hash)
                : ObjectUtils.nullSafeEquals(hash, Crypt.crypt(password, hash));
    }

    protected List<Visa> getVisas(String jwtToken, DecodedJWT decodedJWT) {
        boolean isVisa = decodedJWT.getClaims().containsKey("ga4gh_visa_v1");
        Collection<Visa> visas = new ArrayList<>();
        if (isVisa) {
            getVisa(jwtToken).ifPresent(visas::add);
        } else {
            visas.addAll(getVisas(jwtToken));
        }
        return visas
                .stream()
                .filter(v -> v.getType().equalsIgnoreCase(VisaType.ControlledAccessGrants.name()))
                .collect(Collectors.toList());
    }

    protected Collection<Visa> getVisas(String accessToken) {
        Collection<String> visaTokens;
        try {
            String passportPublicKey = Files.readString(Path.of(passportPublicKeyPath));
            visaTokens = Clearinghouse.INSTANCE.getVisaTokensWithPEMPublicKey(accessToken, passportPublicKey);
        } catch (IOException e) {
            visaTokens = Clearinghouse.INSTANCE.getVisaTokens(accessToken, openIDConfigurationURL);
        }
        return visaTokens
                .stream()
                .map(this::getVisa)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    protected Optional<Visa> getVisa(String visaToken) {
        try {
            String visaPublicKey = Files.readString(Path.of(visaPublicKeyPath));
            return Clearinghouse.INSTANCE.getVisaWithPEMPublicKey(visaToken, visaPublicKey);
        } catch (IOException e) {
            return Clearinghouse.INSTANCE.getVisa(visaToken);
        }
    }

    protected Optional<String> getBasicAuth() {
        return Optional.ofNullable(request.getHeader(HttpHeaders.AUTHORIZATION));
    }

    protected Optional<String> getBearerAuth() {
        return Optional.ofNullable(request.getHeader(HttpHeaders.PROXY_AUTHORIZATION));
    }

}
