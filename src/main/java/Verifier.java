import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.FileHandler;
import java.util.logging.Logger;

public class Verifier {

    private static final DateTimeFormatter dtf = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final SSLContext SSL_CONTEXT;

    private static final String DELIM = ", ";

    public static void main(String[] args) throws Exception {
        Properties props = System.getProperties();
        props.setProperty("jdk.internal.httpclient.disableHostnameVerification", Boolean.TRUE.toString());

        String url = args[0];
        String dateFromStr = args[1];
        String dateToStr = args[2];
        String period = args[3];
        String maxInQueue = args[4];
        Integer minutesToDelay = Integer.valueOf(args[5]);
        boolean executeAutoCorrection = args.length > 6 && "true".equals(args[6]);
        boolean commonMode = args.length > 7 && "true".equals(args[7]);

        FileHandler handler = new FileHandler("verifier.log", true);

        Logger logger = Logger.getLogger("org.example");
        logger.addHandler(handler);

        logger.info("Executing with params url: " + url + "; dateFrom: " + dateFromStr +
                "; dateTo: " + dateToStr + "; period: " + period);

        try {
            LocalDateTime dateFrom = LocalDateTime.from(dtf.parse(dateFromStr));
            LocalDateTime finalDateTo = LocalDateTime.from(dtf.parse(dateToStr));
            StringBuilder sb = new StringBuilder("(");
            UUID globalReviewId = UUID.randomUUID();
            List<UUID> reviewIds = new ArrayList<UUID>();

            while (dateFrom.isBefore(finalDateTo)) {
                LocalDateTime dateTo = plusPeriod(dateFrom, period);
                try {
                    logger.info("");
                    UUID reviewId = executeVerify(url, dateFrom, dateTo.isBefore(finalDateTo) ? dateTo : finalDateTo,
                            logger, minutesToDelay, commonMode, globalReviewId);
                    reviewIds.add(reviewId);
                    if (executeAutoCorrection) {
                        logger.info("Result verify: " + reviewId);
                        if (!commonMode) {
                            logger.info("Result resendSale: " +
                                    executeResendSale(url, reviewId, logger, maxInQueue, minutesToDelay, null));
                            logger.info("Result resendWin: " +
                                    executeResendWin(url, reviewId, logger, maxInQueue, minutesToDelay, null));
                        }
                    } else {
                        sb.append("'" + reviewId.toString() + "'" + DELIM);
                    }
                } catch (Exception e) {
                    logger.warning(e.getMessage());
                    continue;
                } finally {
                    dateFrom = dateTo;
                }
            }
            if (sb.length() > 1) {
                sb.replace(sb.lastIndexOf(DELIM), sb.lastIndexOf(DELIM) + DELIM.length(), ")");
                logger.info(sb.toString());
            }

            if (executeAutoCorrection && commonMode) {
                for (UUID reviewId : reviewIds) {
                    logger.info("Result resendSale: " +
                            executeResendSale(url, reviewId, logger, maxInQueue, minutesToDelay, globalReviewId));
                    logger.info("Result resendWin: " +
                            executeResendWin(url, reviewId, logger, maxInQueue, minutesToDelay, globalReviewId));
                }
            }
        } catch (Exception e) {
            logger.warning(e.getMessage());
        }
    }

    private static LocalDateTime plusPeriod(LocalDateTime dateFrom, String period) {
        return dateFrom.plus(Long.valueOf(period), ChronoUnit.MINUTES);
    }

    private static UUID executeVerify(String url, LocalDateTime dateFrom, LocalDateTime dateTo, Logger logger,
                                      Integer minutesToDelay, boolean commonMode, UUID globalReviewId) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(SSL_CONTEXT)
                .build();

        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> map = Map.of("periodStart", dateFrom.format(dtf),
                "periodEnd", dateTo.format(dtf));
        String requestBody = objectMapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(map);
        logger.info("Execute verify: " + requestBody);
        String urlParams = commonMode ? "?globalReviewId=" + globalReviewId : "";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url + "/api/v2/verify/make" + urlParams))
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code != 200) {
            logger.info("verify finished with error " + code + ", " + response.body());
            TimeUnit.MINUTES.sleep(minutesToDelay);
            executeVerify(url, dateFrom, dateTo, logger, minutesToDelay, commonMode, globalReviewId);
        }
        Gson gson = new GsonBuilder().create();
        ReviewResponse reviewResponse = gson.fromJson(response.body(), ReviewResponse.class);
        return reviewResponse.getReviewId();
    }

    private static String executeResendSale(String url, UUID reviewId, Logger logger, String maxInQueue,
                                            Integer minutesToDelay, UUID globalReviewId) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(SSL_CONTEXT)
                .build();

        String urlParams = globalReviewId == null ? "" : "&globalReviewId=" + globalReviewId;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url + "/api/v1/fake/sale/auto?reviewId=" + reviewId +
                                "&maxInQueue=" + maxInQueue + urlParams))
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code != 200) {
            logger.info("resendSale finished with error " + code + ", " + response.body());
            TimeUnit.MINUTES.sleep(minutesToDelay);
            executeResendSale(url, reviewId, logger, maxInQueue, minutesToDelay, globalReviewId);
        }
        return response.body();
    }

    private static String executeResendWin(String url, UUID reviewId, Logger logger, String maxInQueue,
                                           Integer minutesToDelay, UUID globalReviewId) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(SSL_CONTEXT)
                .build();

        String urlParams = globalReviewId == null ? "" : "&globalReviewId=" + globalReviewId;
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(url + "/api/v1/fake/win/auto?reviewId=" + reviewId +
                                "&maxInQueue=" + maxInQueue + urlParams))
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code != 200) {
            logger.info("resendWin finished with error " + code + ", " + response.body());
            TimeUnit.MINUTES.sleep(minutesToDelay);
            executeResendWin(url, reviewId, logger, maxInQueue, minutesToDelay, globalReviewId);
        }
        return response.body();
    }

    private class ReviewResponse {

        private UUID reviewId;

        public ReviewResponse(UUID reviewId) {
            this.reviewId = reviewId;
        }

        public UUID getReviewId() {
            return reviewId;
        }

        public void setReviewId(UUID reviewId) {
            this.reviewId = reviewId;
        }
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

    static {
        try {
            SSL_CONTEXT = SSLContext.getInstance("TLS");
            SSL_CONTEXT.init(null, trustAllCerts, new SecureRandom());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
