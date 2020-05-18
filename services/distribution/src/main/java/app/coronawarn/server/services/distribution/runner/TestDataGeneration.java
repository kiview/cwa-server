package app.coronawarn.server.services.distribution.runner;

import app.coronawarn.server.common.persistence.domain.DiagnosisKey;
import app.coronawarn.server.common.persistence.service.DiagnosisKeyService;
import app.coronawarn.server.common.protocols.internal.RiskLevel;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.apache.commons.math3.distribution.PoissonDistribution;
import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Generates random diagnosis keys for the time frame between the last diagnosis key in the database and now (last full
 * hour) and writes them to the database. If there are no diagnosis keys in the database yet, then random diagnosis keys
 * for the time frame between the last full hour and the beginning of the retention period (14 days ago) will be
 * generated. The average number of exposure keys to be generated per hour is configurable in the application properties
 * (profile = {@code testdata}).
 */
@Component
@Order(-1)
@Profile("testdata")
public class TestDataGeneration implements ApplicationRunner {

  private final Logger logger = LoggerFactory.getLogger(TestDataGeneration.class);

  @Value("${services.distribution.retention_days}")
  private Integer retentionDays;

  @Value("${services.distribution.testdata.seed}")
  private Integer seed;

  @Value("${services.distribution.testdata.exposures_per_hour}")
  private Integer exposuresPerHour;

  @Autowired
  private DiagnosisKeyService diagnosisKeyService;

  private RandomGenerator random = new JDKRandomGenerator();

  private static final int POISSON_MAX_ITERATIONS = 10_000_000;
  private static final double POISSON_EPSILON = 1e-12;
  private PoissonDistribution poisson;

  // The submission timestamp is counted in 1 hour intervals since epoch
  private static final long ONE_HOUR_INTERVAL_SECONDS = TimeUnit.HOURS.toSeconds(1);

  // The rolling start number is counted in 10 minute intervals since epoch
  private static final long TEN_MINUTES_INTERVAL_SECONDS = TimeUnit.MINUTES.toSeconds(10);

  /**
   * See {@link TestDataGeneration} class documentation.
   */
  @Override
  public void run(ApplicationArguments args) {
    writeTestData();
  }

  /**
   * See {@link TestDataGeneration} class documentation.
   */
  private void writeTestData() {
    logger.debug("Querying diagnosis keys from the database...");
    List<DiagnosisKey> existingDiagnosisKeys = diagnosisKeyService.getDiagnosisKeys();

    // Timestamps in hours since epoch
    long startTimestamp = getGeneratorStartTimestamp(existingDiagnosisKeys);
    long endTimestamp = getGeneratorEndTimestamp();

    // Add the startTimestamp to the seed. Otherwise we would generate the same data every hour.
    random.setSeed(seed + startTimestamp);
    poisson =
        new PoissonDistribution(random, exposuresPerHour, POISSON_EPSILON, POISSON_MAX_ITERATIONS);

    logger.debug("Generating diagnosis keys between {} and {}...", startTimestamp, endTimestamp);
    List<DiagnosisKey> newDiagnosisKeys = LongStream.range(startTimestamp, endTimestamp)
        .mapToObj(submissionTimestamp -> IntStream.range(0, poisson.sample())
            .mapToObj(__ -> generateDiagnosisKey(submissionTimestamp))
            .collect(Collectors.toList()))
        .flatMap(List::stream)
        .collect(Collectors.toList());

    logger.debug("Writing {} new diagnosis keys to the database...", newDiagnosisKeys.size());
    diagnosisKeyService.saveDiagnosisKeys(newDiagnosisKeys);

    logger.debug("Test data generation finished successfully.");
  }

  /**
   * Returns the submission timestamp (in 1 hour intervals since epoch) of the last diagnosis key in the database (or
   * the result of {@link TestDataGeneration#getRetentionStartTimestamp} if there are no diagnosis keys in the database
   * yet.
   */
  private long getGeneratorStartTimestamp(List<DiagnosisKey> diagnosisKeys) {
    if (diagnosisKeys.isEmpty()) {
      return getRetentionStartTimestamp();
    } else {
      DiagnosisKey latestDiagnosisKey = diagnosisKeys.get(diagnosisKeys.size() - 1);
      return latestDiagnosisKey.getSubmissionTimestamp();
    }
  }

  /**
   * Returns the timestamp (in 1 hour intervals since epoch) of the last full hour. Example: If called at 15:38 UTC,
   * this function would return the timestamp for today 14:00 UTC.
   */
  private long getGeneratorEndTimestamp() {
    return (LocalDateTime.now().toEpochSecond(ZoneOffset.UTC) / ONE_HOUR_INTERVAL_SECONDS) - 1;
  }

  /**
   * Returns the timestamp (in 1 hour intervals since epoch) at which the retention period starts. Example: If the
   * retention period in the application properties is set to 14 days, then this function would return the timestamp for
   * 14 days ago (from now) at 00:00 UTC.
   */
  private long getRetentionStartTimestamp() {
    return LocalDate.now().minusDays(retentionDays).atStartOfDay()
        .toEpochSecond(ZoneOffset.UTC) / ONE_HOUR_INTERVAL_SECONDS;
  }

  /**
   * Returns a random diagnosis key with a specific submission timestamp.
   */
  private DiagnosisKey generateDiagnosisKey(long submissionTimestamp) {
    return DiagnosisKey.builder()
        .withKeyData(generateDiagnosisKeyBytes())
        .withRollingStartNumber(generateRollingStartNumber(submissionTimestamp))
        .withRollingPeriod(generateRollingPeriod())
        .withTransmissionRiskLevel(generateTransmissionRiskLevel())
        .withSubmissionTimestamp(submissionTimestamp)
        .build();
  }

  /**
   * Returns 16 random bytes.
   */
  private byte[] generateDiagnosisKeyBytes() {
    byte[] exposureKey = new byte[16];
    random.nextBytes(exposureKey);
    return exposureKey;
  }

  /**
   * Returns a random rolling start number (timestamp since when a key was active, represented by a 10 minute interval
   * counter.) between a specific submission timestamp and the beginning of the retention period.
   */
  private long generateRollingStartNumber(long submissionTimestamp) {
    long maxRollingStartNumber =
        submissionTimestamp * ONE_HOUR_INTERVAL_SECONDS / TEN_MINUTES_INTERVAL_SECONDS;
    long minRollingStartNumber =
        maxRollingStartNumber
            - TimeUnit.DAYS.toSeconds(retentionDays) / TEN_MINUTES_INTERVAL_SECONDS;
    return getRandomBetween(minRollingStartNumber, maxRollingStartNumber);
  }

  /**
   * Returns a rolling period (number of 10 minute intervals that a key was active for) of 1 day.
   */
  private long generateRollingPeriod() {
    return TimeUnit.DAYS.toSeconds(1) / TEN_MINUTES_INTERVAL_SECONDS;
  }

  /**
   * Returns a random number between {@link RiskLevel#RISK_LEVEL_LOWEST_VALUE} and {@link
   * RiskLevel#RISK_LEVEL_HIGHEST_VALUE}.
   */
  private int generateTransmissionRiskLevel() {
    return Math.toIntExact(
        getRandomBetween(RiskLevel.RISK_LEVEL_LOWEST_VALUE, RiskLevel.RISK_LEVEL_HIGHEST_VALUE));
  }

  /**
   * Returns a random number between {@code minIncluding} and {@code maxIncluding}.
   */
  private long getRandomBetween(long minIncluding, long maxIncluding) {
    return minIncluding + (long) (random.nextDouble() * (maxIncluding - minIncluding));
  }
}
