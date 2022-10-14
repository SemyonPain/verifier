import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class Verifier {

    private static final DateTimeFormatter dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static void main(String[] args) throws Exception {
        Properties props = System.getProperties();
        props.setProperty("jdk.internal.httpclient.disableHostnameVerification", Boolean.TRUE.toString());

        String url = args[0];
        String dateFromStr = args[1];
        String dateToStr = args[2];
        String period = args[3];

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());

        FileHandler handler = new FileHandler("verifier.log", true);

        Logger logger = Logger.getLogger("org.example");
        logger.addHandler(handler);

        logger.info("Executing with params url: " + url + "; dateFrom: " + dateFromStr +
                "; dateTo: " + dateToStr + "; period: " + period);

        try {
            LocalDateTime dateFrom = LocalDateTime.from(dtf.parse(dateFromStr));
            LocalDateTime finalDateTo = LocalDateTime.from(dtf.parse(dateToStr));

            while (dateFrom.isBefore(finalDateTo)) {
                LocalDateTime dateTo = plusPeriod(dateFrom, period);
                try {
                    logger.info("");
                    logger.info("Result verify: " + executeVerify(url,
                            dateFrom, dateTo.isBefore(finalDateTo) ? dateTo : finalDateTo, logger, sslContext));
                    logger.info("Result resendSale: " + executeResendSale(url, sslContext));
                    logger.info("Result resendWin: " + executeResendWin(url, sslContext));
                } catch (Exception e) {
                    logger.warning(e.getMessage());
                    continue;
                } finally {
                    dateFrom = dateTo;
                }
            }
        } catch (Exception e) {
            logger.warning(e.getMessage());
        }
    }

    private static LocalDateTime plusPeriod(LocalDateTime dateFrom, String period) {
        return dateFrom.plus(Long.valueOf(period), ChronoUnit.MINUTES);
    }

    private static TrustManager[] trustAllCerts = new TrustManager[]{
            new X509ExtendedTrustManager() {
                @Override
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return new java.security.cert.X509Certificate[0];
                }

                @Override
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
                }

                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
                }
            }
    };

    private static int executeVerify(String url, LocalDateTime dateFrom, LocalDateTime dateTo,
                                     Logger logger, SSLContext sslContext) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();

        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> map = Map.of("periodStart", dateFrom.format(dtf),
                "periodEnd", dateTo.format(dtf));
        String requestBody = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(map);
        logger.info("Execute verify: " + requestBody);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url + "/api/v2/verify/make"))
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private static String executeResendSale(String url, SSLContext sslContext) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url + "/api/v1/fake/sale/auto"))
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private static String executeResendWin(String url, SSLContext sslContext) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .build();

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url + "/api/v1/fake/win/auto"))
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }
}
