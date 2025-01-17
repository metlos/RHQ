/*
 *
 *  * RHQ Management Platform
 *  * Copyright (C) 2005-2012 Red Hat, Inc.
 *  * All rights reserved.
 *  *
 *  * This program is free software; you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License, version 2, as
 *  * published by the Free Software Foundation, and/or the GNU Lesser
 *  * General Public License, version 2.1, also as published by the Free
 *  * Software Foundation.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  * GNU General Public License and the GNU Lesser General Public License
 *  * for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * and the GNU Lesser General Public License along with this program;
 *  * if not, write to the Free Software Foundation, Inc.,
 *  * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *
 */

package org.rhq.server.metrics;

import static java.util.Arrays.asList;
import static org.joda.time.DateTime.now;
import static org.rhq.test.AssertUtils.assertCollectionMatchesNoOrder;
import static org.rhq.test.AssertUtils.assertPropertiesMatch;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.Futures;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.joda.time.Minutes;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.rhq.core.domain.measurement.MeasurementDataNumeric;
import org.rhq.core.domain.measurement.composite.MeasurementDataNumericHighLowComposite;
import org.rhq.server.metrics.domain.AggregateNumericMetric;
import org.rhq.server.metrics.domain.AggregateType;
import org.rhq.server.metrics.domain.MetricsIndexEntry;
import org.rhq.server.metrics.domain.MetricsTable;
import org.rhq.server.metrics.domain.RawNumericMetric;
import org.rhq.server.metrics.domain.RawNumericMetricMapper;
import org.rhq.server.metrics.domain.SimplePagedResult;

/**
 * @author John Sanda
 */
public class MetricsServerTest extends CassandraIntegrationTest {

    private static final boolean ENABLED = true;

    private static final double TEST_PRECISION = Math.pow(10, -9);

    private final Log log = LogFactory.getLog(MetricsServerTest.class);

    private final long SECOND = 1000;

    private final long MINUTE = 60 * SECOND;

    private MetricsServerStub metricsServer;

    private MetricsDAO dao;

    private DateTimeServiceStub dateTimeService;

    private MetricsConfiguration configuration = new MetricsConfiguration();

    private static class MetricsServerStub extends MetricsServer {
        private DateTime currentHour;

        public void setCurrentHour(DateTime currentHour) {
            this.currentHour = currentHour;
        }

        @Override
        protected DateTime currentHour() {
            if (currentHour == null) {
                return super.currentHour();
            }
            return currentHour;
        }

    }

    private static class DateTimeServiceStub extends DateTimeService {

        private DateTime now;

        public void setNow(DateTime now) {
            this.now = now;
        }

        @Override
        public DateTime now() {
            if (now == null) {
                return super.now();
            }
            return now;
        }

        @Override
        public long nowInMillis() {
            if (now == null) {
                return super.nowInMillis();
            }
            return now.getMillis();
        }
    }

    @BeforeMethod
    public void initServer() throws Exception {
        metricsServer = new MetricsServerStub();
        metricsServer.setConfiguration(configuration);

        dateTimeService = new DateTimeServiceStub();
        dateTimeService.setConfiguration(configuration);
        metricsServer.setDateTimeService(dateTimeService);

        dao = new MetricsDAO(storageSession, configuration);
        metricsServer.setDAO(dao);
        metricsServer.init();

        purgeDB();
    }

    private void setNow(DateTime now) {
        metricsServer.setCurrentHour(now.minusHours(1));
        dateTimeService.setNow(now);
    }

    private void purgeDB() throws Exception {
        session.execute("TRUNCATE " + MetricsTable.RAW);
        session.execute("TRUNCATE " + MetricsTable.ONE_HOUR);
        session.execute("TRUNCATE " + MetricsTable.SIX_HOUR);
        session.execute("TRUNCATE " + MetricsTable.TWENTY_FOUR_HOUR);
        session.execute("TRUNCATE " + MetricsTable.INDEX);
    }

    @Test(enabled = ENABLED)
    public void insertMultipleRawNumericDataForOneSchedule() throws Exception {
        int scheduleId = 123;

        DateTime hour0 = hour0();
        DateTime currentTime = hour0.plusHours(4).plusMinutes(44);
        DateTime threeMinutesAgo = currentTime.minusMinutes(3);
        DateTime twoMinutesAgo = currentTime.minusMinutes(2);
        DateTime oneMinuteAgo = currentTime.minusMinutes(1);

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(threeMinutesAgo.getMillis(), scheduleId, 3.2));
        data.add(new MeasurementDataNumeric(twoMinutesAgo.getMillis(), scheduleId, 3.9));
        data.add(new MeasurementDataNumeric(oneMinuteAgo.getMillis(), scheduleId, 2.6));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.size());

        long timestamp = System.currentTimeMillis();
        metricsServer.addNumericData(data, waitForRawInserts);

        waitForRawInserts.await("Failed to insert raw data");

        List<RawNumericMetric> actual = Lists.newArrayList(dao.findRawMetrics(scheduleId, hour0.plusHours(4)
            .getMillis(), hour0.plusHours(5).getMillis()));
        List<RawNumericMetric> expected = asList(
            new RawNumericMetric(scheduleId, threeMinutesAgo.getMillis(), 3.2),
            new RawNumericMetric(scheduleId, twoMinutesAgo.getMillis(), 3.9),
            new RawNumericMetric(scheduleId, oneMinuteAgo.getMillis(), 2.6)
        );

        assertEquals(actual, expected, "Failed to retrieve raw metric data");
        assertColumnMetadataEquals(scheduleId, hour0.plusHours(4), hour0.plusHours(5), MetricsTable.RAW.getTTL(),
            timestamp);

        DateTime hour4 = hour0.plusHours(4);

        List<MetricsIndexEntry> expectedIndex = asList(new MetricsIndexEntry(MetricsTable.ONE_HOUR, hour4,
            scheduleId));
        assertMetricsIndexEquals(MetricsTable.ONE_HOUR, hour4.getMillis(), expectedIndex,
            "Failed to update index for " + MetricsTable.ONE_HOUR);
    }

    @Test(enabled = ENABLED)
    public void calculateAggregatesForOneScheduleWhenDBIsEmpty() throws Exception {
        int scheduleId = 123;

        DateTime hour0 = hour0();
        DateTime hour6 = hour0.plusHours(6);
        DateTime lastHour = hour6.minusHours(1);
        DateTime firstMetricTime = hour6.minusMinutes(3);
        DateTime secondMetricTime = hour6.minusMinutes(2);
        DateTime thirdMetricTime = hour6.minusMinutes(1);

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(firstMetricTime.getMillis(), scheduleId, 3.2));
        data.add(new MeasurementDataNumeric(secondMetricTime.getMillis(), scheduleId, 3.9));
        data.add(new MeasurementDataNumeric(thirdMetricTime.getMillis(), scheduleId, 2.6));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.size());

        setNow(hour6.plusHours(1));
        metricsServer.addNumericData(data, waitForRawInserts);
        waitForRawInserts.await("Failed to insert raw data");

        metricsServer.calculateAggregates();

        // verify that one hour metric data is updated
        List<AggregateNumericMetric> expected = asList(new AggregateNumericMetric(scheduleId,
            divide((3.9 + 3.2 + 2.6), 3), 2.6, 3.9, lastHour.getMillis()));
        assert1HourDataEquals(scheduleId, expected);

        // verify that 6 hour metric data is updated
        assert6HourDataEquals(scheduleId, asList(new AggregateNumericMetric(scheduleId, divide((3.9 + 3.2 + 2.6), 3),
            2.6, 3.9, hour0.getMillis())));

        // TODO verify that 24 hour data is *not* updated
        // TODO verify metrics index for 24 hour data is updated
    }

    @Test(enabled = ENABLED)
    public void aggregateRawDataDuring9thHour() throws Exception {
        int scheduleId = 123;

        DateTime hour0 = hour0();
        DateTime hour9 = hour0.plusHours(9);
        DateTime hour8 = hour9.minusHours(1);

        DateTime firstMetricTime = hour8.plusMinutes(5);
        DateTime secondMetricTime = hour8.plusMinutes(10);
        DateTime thirdMetricTime = hour8.plusMinutes(15);

        double firstValue = 1.1;
        double secondValue = 2.2;
        double thirdValue = 3.3;

        Set<MeasurementDataNumeric> rawMetrics = new HashSet<MeasurementDataNumeric>();
        rawMetrics.add(new MeasurementDataNumeric(firstMetricTime.getMillis(), scheduleId, firstValue));
        rawMetrics.add(new MeasurementDataNumeric(secondMetricTime.getMillis(), scheduleId, secondValue));
        rawMetrics.add(new MeasurementDataNumeric(thirdMetricTime.getMillis(), scheduleId, thirdValue));

        WaitForWrite waitForRawInserts = new WaitForWrite(rawMetrics.size());

        for (MeasurementDataNumeric raw : rawMetrics) {
            StorageResultSetFuture resultSetFuture = dao.insertRawData(raw);
            Futures.addCallback(resultSetFuture, waitForRawInserts);
        }
        waitForRawInserts.await("Failed to insert raw data");

        WaitForRawInserts waitForIndexUpdates = new WaitForRawInserts(rawMetrics.size());
        AtomicInteger remainingUpdates = new AtomicInteger(rawMetrics.size());
        for (MeasurementDataNumeric raw : rawMetrics) {
            metricsServer.updateMetricsIndex(raw, rawMetrics.size(), remainingUpdates, hour8.getMillis(),
                waitForIndexUpdates);
        }
        waitForIndexUpdates.await("Failed to update metrics index for raw data");

        setNow(hour9.plusHours(1));
        metricsServer.calculateAggregates();

        // verify that the 1 hour aggregates are calculated
        assert1HourDataEquals(scheduleId, asList(new AggregateNumericMetric(scheduleId, divide((1.1 + 2.2 + 3.3), 3),
            firstValue, thirdValue, hour8.getMillis())));

        // verify that the 6 hour index is updated
        List<MetricsIndexEntry> expected6HourIndex = asList(new MetricsIndexEntry(MetricsTable.SIX_HOUR,
            dateTimeService.getTimeSlice(hour9, Minutes.minutes(60 * 6)), scheduleId));

        assertMetricsIndexEquals(MetricsTable.SIX_HOUR, hour9.minusHours(3).getMillis(), expected6HourIndex,
            "Failed to update index for " + MetricsTable.SIX_HOUR);

        // The 6 hour data should not get aggregated since the current 6 hour time slice
        // has not passed yet. More specifically, the aggregation job is running at 09:00
        // which means that the current 6 hour slice is from 06:00 to 12:00.
        assert6HourDataEmpty(scheduleId);

        // verify that the 24 hour index is empty
        assert24HourMetricsIndexEmpty(scheduleId, hour0.getMillis());

        // verify that the 1 hour queue has been purged
        assert1HourMetricsIndexEmpty(scheduleId, hour9.getMillis());
    }

    @Test(enabled = ENABLED)
    public void aggregate1HourDataDuring12thHour() {
        // set up the test fixture
        int scheduleId = 123;

        DateTime hour0 = hour0();
        DateTime hour6 = hour0.plusHours(6);
        DateTime hour7 = hour0.plusHours(7);
        DateTime hour8 = hour0.plusHours(8);

        double min1 = 1.1;
        double avg1 = 2.2;
        double max1 = 9.9;

        double min2 = 4.4;
        double avg2 = 5.5;
        double max2 = 6.6;

        // insert one hour data to be aggregated
        List<AggregateNumericMetric> oneHourMetrics = asList(
            new AggregateNumericMetric(scheduleId, avg1, min1, max1, hour7.getMillis()),
            new AggregateNumericMetric(scheduleId, avg2, min2, max2, hour8.getMillis())
        );
        for (AggregateNumericMetric metric : oneHourMetrics) {
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MIN, metric.getMin());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MAX, metric.getMax());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.AVG, metric.getAvg());
        }

        // update the 6 hour queue
        Map<Integer, Long> indexUpdates = new HashMap<Integer, Long>();
        indexUpdates.put(scheduleId, hour6.getMillis());
        dao.updateMetricsIndex(MetricsTable.SIX_HOUR, indexUpdates);

        // execute the system under test
        setNow(hour0().plusHours(13));
        metricsServer.calculateAggregates();

        // verify the results
        // verify that the one hour data has been aggregated
        assert6HourDataEquals(scheduleId, asList(new AggregateNumericMetric(scheduleId, divide((avg1 + avg2), 2), min1,
            max1, hour6.getMillis())));

        // verify that the 6 hour queue has been updated
        assert6HourMetricsIndexEmpty(scheduleId, hour6.getMillis());

        // verify that the 24 hour queue is updated
        assertMetricsIndexEquals(MetricsTable.TWENTY_FOUR_HOUR, hour0.getMillis(), asList(new MetricsIndexEntry(
            MetricsTable.TWENTY_FOUR_HOUR, hour0, scheduleId)), "Failed to update index for "
            + MetricsTable.TWENTY_FOUR_HOUR);

        // verify that 6 hour data is not rolled up into the 24 hour bucket
        assert24HourDataEmpty(scheduleId);
    }

    /**
     * This test exercises the scenario in which there is raw data from the past hour to be
     * aggregated as well as from an earlier period. This could happen in the event of a
     * server shutdown. Suppose that the most recently aggregated data is from hour 9 and
     * that the current hour is 15. This means we had a server shutdown some time during
     * hour 10 which also mean we could have raw data in the 10:00 hour in addition to the
     * previous hour that need to be aggregated.
     */
    @Test(enabled = true)
    public void runAggregationIn15thHourAfterServerOutage() throws Exception {
        int scheduleId = 123;
        DateTime hour10 = hour0().plusHours(10);
        DateTime hour14 = hour0().plusHours(14);
        DateTime hour15 = hour0().plusHours(15);

        // first we insert raw data during hour 10 before the server shutdown
        Set<MeasurementDataNumeric> rawData = new HashSet<MeasurementDataNumeric>();
        rawData.add(new MeasurementDataNumeric(hour10.plusMinutes(5).getMillis(), scheduleId, 5.0));
        rawData.add(new MeasurementDataNumeric(hour10.plusMinutes(10).getMillis(), scheduleId, 10.0));
        rawData.add(new MeasurementDataNumeric(hour10.plusMinutes(15).getMillis(), scheduleId, 15.0));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(rawData.size());

        metricsServer.addNumericData(rawData, waitForRawInserts);
        waitForRawInserts.await("Failed to insert raw data during hour " + hour10.getHourOfDay());

        // now after the server starts back up in the 14th hour,
        //
        //  2) re-initialize the metrics server
        //  3) insert some more raw data
        setNow(hour0().plusHours(16));
        metricsServer.init();

        rawData = new HashSet<MeasurementDataNumeric>();
        rawData.add(new MeasurementDataNumeric(hour14.plusMinutes(20).getMillis(), scheduleId, 3.0));
        rawData.add(new MeasurementDataNumeric(hour14.plusMinutes(25).getMillis(), scheduleId, 5.0));
        rawData.add(new MeasurementDataNumeric(hour14.plusMinutes(30).getMillis(), scheduleId, 13.0));

        waitForRawInserts = new WaitForRawInserts(rawData.size());
        metricsServer.addNumericData(rawData, waitForRawInserts);
        waitForRawInserts.await("Failed to insert raw data during hour " + hour14.getHourOfDay());

        // Now let's assume we have reached the top of the hour and run the scheduled
        // aggregation.
        metricsServer.calculateAggregates();

        // verify that we have one hour aggregates
        double hour10Avg = divide(5.0 + 10.0 + 15.0, 3);
        double hour10Min = 5.0;
        double hour10Max = 15.0;

        double hour14Avg = divide(3.0 + 5.0 + 13.0, 3);
        double hour14Min = 3.0;
        double hour14Max = 13.0;

        List<AggregateNumericMetric> expectedOneHourData = asList(
            // add aggregate for hour 10
            new AggregateNumericMetric(scheduleId, hour10Avg, hour10Min, hour10Max, hour10.getMillis()),
            // add aggregate for hour 14
            new AggregateNumericMetric(scheduleId, hour14Avg, hour14Min, hour14Max, hour14.getMillis())
        );
        assert1HourDataEquals(scheduleId, expectedOneHourData);

        // verify that we have 6 hour aggregates for hour 6. The data from the
        // 10:00 hour falls into the 6:00 - 12:00 time slice so we should have
        // a 6 hour aggregate.
        assert6HourDataEquals(scheduleId, asList(new AggregateNumericMetric(scheduleId, hour10Avg, hour10Min,
            hour10Max, hour0().plusHours(6).getMillis())));
    }

    @Test(enabled = true)
    public void runAggregationIn8thHourAfterServerOutageFromPreviousDay() throws Exception {
        int scheduleId = 123;
        DateTime hour20Yesterday = hour0().minusHours(4);
        DateTime hour18Yesterday = hour0().minusHours(6);
        DateTime hour0Yesterday = hour0().minusDays(1);
        DateTime hour8 = hour0().plusHours(8);
        DateTime hour9 = hour0().plusHours(9);

        // insert data before server shutdown
        Set<MeasurementDataNumeric> rawData = new HashSet<MeasurementDataNumeric>();
        rawData.add(new MeasurementDataNumeric(hour20Yesterday.plusMinutes(5).getMillis(), scheduleId, 7.0));
        rawData.add(new MeasurementDataNumeric(hour20Yesterday.plusMinutes(10).getMillis(), scheduleId, 2.5));
        rawData.add(new MeasurementDataNumeric(hour20Yesterday.plusMinutes(15).getMillis(), scheduleId, 4.0));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(rawData.size());

        metricsServer.addNumericData(rawData, waitForRawInserts);
        waitForRawInserts.await("Failed to insert raw data during hour " + hour20Yesterday.getHourOfDay() +
            " from yesterday");

        // now after the server starts back up in the 8th hour,
        //
        //  2) re-initialize the metrics server
        //  3) insert some more raw data
        setNow(hour0().plusHours(10));
        metricsServer.init();

        rawData = new HashSet<MeasurementDataNumeric>();
        rawData.add(new MeasurementDataNumeric(hour8.plusMinutes(20).getMillis(), scheduleId, 8.0));
        rawData.add(new MeasurementDataNumeric(hour8.plusMinutes(25).getMillis(), scheduleId, 16.0));
        rawData.add(new MeasurementDataNumeric(hour8.plusMinutes(30).getMillis(), scheduleId, 5.0));

        waitForRawInserts = new WaitForRawInserts(rawData.size());
        metricsServer.addNumericData(rawData, waitForRawInserts);
        waitForRawInserts.await("Failed to insert raw data during hour " + hour8.getHourOfDay());

        // Now let's assume we have reached the top of the hour and run the scheduled
        // aggregation.
        metricsServer.calculateAggregates();

        // verify that we have one hour aggregates
        double hour20YesterdayAvg = divide(7.0 + 2.5 + 4.0, 3);
        double hour20YesterdayMin = 2.5;
        double hour20YesterdayMax = 7.0;

        double hour8Avg = divide(8.0 + 16.0 + 5.0, 3);
        double hour8Min = 5.0;
        double hour8Max = 16.0;

        List<AggregateNumericMetric> expectedOneHourData = asList(
            new AggregateNumericMetric(scheduleId, hour20YesterdayAvg, hour20YesterdayMin, hour20YesterdayMax, hour20Yesterday.getMillis()),
            new AggregateNumericMetric(scheduleId, hour8Avg, hour8Min, hour8Max, hour8.getMillis())
        );

        assert1HourDataEquals(scheduleId, expectedOneHourData);

        // verify that we a 6 hour aggregate for the previous day's 18:00 - 00:00
        // time slice
        assert6HourDataEquals(scheduleId, asList(new AggregateNumericMetric(scheduleId, hour20YesterdayAvg,
            hour20YesterdayMin, hour20YesterdayMax, hour18Yesterday.getMillis())));

        // verify that we have a 24 hour aggregate for the previous day's data
        assert24HourDataEquals(scheduleId, asList(new AggregateNumericMetric(scheduleId, hour20YesterdayAvg,
            hour20YesterdayMin, hour20YesterdayMax, hour0Yesterday.getMillis())));
    }

    @Test(enabled = ENABLED)
    public void aggregate6HourDataDuring24thHour() {
        // set up the test fixture
        int scheduleId = 123;

        DateTime hour0 = hour0();
        DateTime hour12 = hour0.plusHours(12);
        DateTime hour6 = hour0.plusHours(6);
        DateTime hour24 = hour0.plusHours(24);

        double min1 = 1.1;
        double avg1 = 2.2;
        double max1 = 3.3;

        double min2 = 4.4;
        double avg2 = 5.5;
        double max2 = 6.6;

        // insert 6 hour data to be aggregated
        List<AggregateNumericMetric> sixHourMetrics = asList(
            new AggregateNumericMetric(scheduleId, avg1, min1, max1, hour6.getMillis()),
            new AggregateNumericMetric(scheduleId, avg2, min2, max2, hour12.getMillis())
        );
        for (AggregateNumericMetric metric : sixHourMetrics) {
            dao.insertSixHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MIN, metric.getMin());
            dao.insertSixHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MAX, metric.getMax());
            dao.insertSixHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.AVG, metric.getAvg());
        }

        // update the 24 queue
        Map<Integer, Long> indexUpdates = new HashMap<Integer, Long>();
        indexUpdates.put(scheduleId, hour0.getMillis());
        dao.updateMetricsIndex(MetricsTable.TWENTY_FOUR_HOUR, indexUpdates);

        // execute the system under test
        setNow(hour24.plusHours(1));
        metricsServer.calculateAggregates();

        // verify the results
        // verify that the 6 hour data is aggregated
        assert24HourDataEquals(scheduleId, asList(new AggregateNumericMetric(scheduleId, divide(avg1 + avg2, 2),
            min1, max2, hour0.getMillis())));

        // verify that the 24 hour queue is updated
        assert24HourMetricsIndexEmpty(scheduleId, hour0.getMillis());
    }

    @Test//(enabled = ENABLED)
    public void findRawDataCompositesForResource() throws Exception {
        DateTime beginTime = now().minusHours(4);
        DateTime endTime = now();
        Buckets buckets = new Buckets(beginTime, endTime);
        int scheduleId = 123;

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 10, scheduleId, 1.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 20, scheduleId, 2.2));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 30, scheduleId, 3.3));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 10, scheduleId, 4.4));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 20, scheduleId, 5.5));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 30, scheduleId, 6.6));

        // add some data outside the range
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() - 100, scheduleId, 1.23));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + buckets.getInterval() + 50, scheduleId,
            4.56));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.size());

        metricsServer.addNumericData(data, waitForRawInserts);
        waitForRawInserts.await("Failed to insert raw data");

        List<MeasurementDataNumericHighLowComposite> actualData = Lists.newArrayList(metricsServer.findDataForResource(
            scheduleId, beginTime.getMillis(), endTime.getMillis(),60));

        assertEquals(actualData.size(), buckets.getNumDataPoints(), "Expected to get back 60 data points.");

        MeasurementDataNumericHighLowComposite expectedBucket0Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(0).getStartTime(), divide(1.1 + 2.2 + 3.3, 3), 3.3, 1.1);
        MeasurementDataNumericHighLowComposite expectedBucket59Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(59).getStartTime(), divide(4.4 + 5.5 + 6.6, 3), 6.6, 4.4);
        MeasurementDataNumericHighLowComposite expectedBucket29Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(29).getStartTime(), Double.NaN, Double.NaN, Double.NaN);

        assertPropertiesMatch("The data for bucket 0 does not match the expected values.", expectedBucket0Data,
            actualData.get(0));
        assertPropertiesMatch("The data for bucket 59 does not match the expected values.", expectedBucket59Data,
            actualData.get(59));
        assertPropertiesMatch("The data for bucket 29 does not match the expected values.", expectedBucket29Data,
            actualData.get(29));
    }

    @Test
    public void findLatestValueForResource() throws Exception {
        int scheduleId = 123;

        DateTime fifteenMinutesAgo = now().minusMinutes(15);
        DateTime tenMinutesAgo = now().minusMinutes(10);
        DateTime fiveMinutesAgo = now().minusMinutes(5);

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(fifteenMinutesAgo.getMillis(), scheduleId, 1.1));
        data.add(new MeasurementDataNumeric(tenMinutesAgo.getMillis(), scheduleId, 2.2));
        data.add(new MeasurementDataNumeric(fiveMinutesAgo.getMillis(), scheduleId, 3.3));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.size());

        metricsServer.addNumericData(data, waitForRawInserts);

        waitForRawInserts.await("Failed to insert raw data");

        RawNumericMetric actual = metricsServer.findLatestValueForResource(scheduleId);
        RawNumericMetric expected = new RawNumericMetric(scheduleId, fiveMinutesAgo.getMillis(), 3.3);

        assertEquals(actual, expected, "Failed to find latest metric value for resource");
    }

    @Test
    public void getSummaryRawAggregateForResource() throws Exception {
        DateTime beginTime = now().minusHours(4);
        DateTime endTime = now();
        Buckets buckets = new Buckets(beginTime, endTime);
        int scheduleId = 123;

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 10, scheduleId, 1.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 20, scheduleId, 2.2));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 30, scheduleId, 3.3));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 10, scheduleId, 4.4));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 20, scheduleId, 5.5));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 30, scheduleId, 6.6));

        // add some data outside the range
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() - 100, scheduleId, 1.23));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + buckets.getInterval() + 50, scheduleId,
            4.56));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.size());

        metricsServer.addNumericData(data, waitForRawInserts);

        waitForRawInserts.await("Failed to insert raw data");

        AggregateNumericMetric actual = metricsServer.getSummaryAggregate(scheduleId, beginTime.getMillis(),
            endTime.getMillis());
        double avg = divide(1.1 + 2.2 + 3.3 + 4.4 + 5.5 + 6.6, 6);
        AggregateNumericMetric expected = new AggregateNumericMetric(0, avg, 1.1, 6.6,
            beginTime.getMillis());

        assertPropertiesMatch("Failed to get resource summary aggregate for raw data.", expected, actual,
            TEST_PRECISION);
    }

    @Test
    public void getSummary1HourAggregateForResource() {
        DateTime beginTime = now().minusDays(11);
        DateTime endTime = now();

        Buckets buckets = new Buckets(beginTime, endTime);
        DateTime bucket0Time = new DateTime(buckets.get(0).getStartTime());
        DateTime bucket59Time = new DateTime(buckets.get(59).getStartTime());

        int scheduleId = 123;
        List<AggregateNumericMetric> metrics = asList(
            new AggregateNumericMetric(scheduleId, 2.0, 1.0, 3.0, bucket0Time.getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 4.0, 6.0, bucket0Time.plusHours(1).getMillis()),
            new AggregateNumericMetric(scheduleId, 3.0, 3.0, 3.0, bucket0Time.plusHours(2).getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 2.0, 9.0, bucket59Time.getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 4.0, 6.0, bucket59Time.plusHours(1).getMillis()),
            new AggregateNumericMetric(scheduleId, 3.0, 3.0, 3.0, bucket59Time.plusHours(2).getMillis())
        );
        for (AggregateNumericMetric metric : metrics) {
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MIN, metric.getMin());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MAX, metric.getMax());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.AVG, metric.getAvg());
        }

        AggregateNumericMetric actual = metricsServer.getSummaryAggregate(scheduleId, beginTime.getMillis(),
            endTime.getMillis());
        double avg = divide(2.0 + 5.0 + 3.0 + 5.0 + 5.0 + 3.0, 6);
        AggregateNumericMetric expected = new AggregateNumericMetric(0, avg, 1.0, 9.0, beginTime.getMillis());

        assertPropertiesMatch("Failed to get resource summary aggregate for one hour data", expected, actual,
            TEST_PRECISION);
    }

    @Test
    public void getSummaryAggregateForGroup() {
        DateTime beginTime = now().minusDays(11);
        DateTime endTime = now();

        Buckets buckets = new Buckets(beginTime, endTime);
        DateTime bucket0Time = new DateTime(buckets.get(0).getStartTime());
        DateTime bucket59Time = new DateTime(buckets.get(59).getStartTime());

        int scheduleId1 = 123;
        int scheduleId2 = 456;

        List<AggregateNumericMetric> metrics = asList(
            new AggregateNumericMetric(scheduleId1, 1.1, 1.1, 1.1, bucket0Time.getMillis()),
            new AggregateNumericMetric(scheduleId2, 1.2, 1.2, 1.2, bucket0Time.getMillis()),

            new AggregateNumericMetric(scheduleId1, 5.1, 5.1, 5.1, bucket59Time.getMillis()),
            new AggregateNumericMetric(scheduleId2, 5.2, 5.2, 5.2, bucket59Time.getMillis())
        );
        for (AggregateNumericMetric metric : metrics) {
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MIN, metric.getMin());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MAX, metric.getMax());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.AVG, metric.getAvg());
        }

        AggregateNumericMetric actual = metricsServer.getSummaryAggregate(asList(scheduleId1, scheduleId2),
            beginTime.getMillis(), endTime.getMillis());
        double avg = divide(1.1 + 1.2 + 5.1 + 5.2, 4);
        AggregateNumericMetric expected = new AggregateNumericMetric(0, avg, 1.1, 5.2, beginTime.getMillis());

//        assertEquals(actual, expected, "Failed to get group summary aggregate for one hour data");
        assertPropertiesMatch("Failed to get group summary aggregate for one hour data", expected, actual,
            TEST_PRECISION);
    }

    @Test
    public void getSummaryRawAggregateForGroup() throws Exception {
        DateTime beginTime = now().minusHours(4);
        DateTime endTime = now();
        Buckets buckets = new Buckets(beginTime, endTime);
        int scheduleId1 = 123;
        int scheduleId2 = 456;

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 10, scheduleId1, 1.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 10, scheduleId2, 1.2));

        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 20, scheduleId1, 2.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 20, scheduleId2, 2.2));

        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 30, scheduleId1, 3.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 30, scheduleId2, 3.2));

        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 10, scheduleId1, 4.1));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 10, scheduleId2, 4.2));

        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 20, scheduleId1, 5.1));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 20, scheduleId2, 5.2));

        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 30, scheduleId1, 6.1));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 30, scheduleId2, 6.2));

        // add some data outside the range
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() - 100, scheduleId1, 1.23));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() - 100, scheduleId2, 2.23));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + buckets.getInterval() + 50, scheduleId1,
            4.56));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + buckets.getInterval() + 50, scheduleId2,
            4.56));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.size());

        metricsServer.addNumericData(data, waitForRawInserts);

        waitForRawInserts.await("Failed to insert raw data");

        AggregateNumericMetric actual = metricsServer.getSummaryAggregate(asList(scheduleId1, scheduleId2),
            beginTime.getMillis(), endTime.getMillis());

        double avg = divide(1.1 + 1.2 + 2.1 + 2.2 + 3.1 + 3.2 + 4.1 + 4.2 + 5.1 + 5.2 + 6.1 + 6.2, 12);
        AggregateNumericMetric expected = new AggregateNumericMetric(0, avg, 1.1, 6.2, beginTime.getMillis());

//        assertEquals(actual, expected, "Failed to get group summary aggregate for raw data.");
        assertPropertiesMatch("Failed to get group summary aggregate for raw data.", expected, actual,
            TEST_PRECISION);
    }

    @Test
    public void findRawDataCompositesForGroup() throws Exception {
        DateTime beginTime = now().minusHours(4);
        DateTime endTime = now();
        Buckets buckets = new Buckets(beginTime, endTime);
        int scheduleId1 = 123;
        int scheduleId2 = 456;

        Set<MeasurementDataNumeric> data = new HashSet<MeasurementDataNumeric>();
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 10, scheduleId1, 1.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 10, scheduleId2, 1.2));

        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 20, scheduleId1, 2.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 20, scheduleId2, 2.2));

        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 30, scheduleId1, 3.1));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() + 30, scheduleId2, 3.2));

        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 10, scheduleId1, 4.1));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 10, scheduleId2, 4.2));

        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 20, scheduleId1, 5.1));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 20, scheduleId2, 5.2));

        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 30, scheduleId1, 6.1));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + 30, scheduleId2, 6.2));

        // add some data outside the range
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() - 100, scheduleId1, 1.23));
        data.add(new MeasurementDataNumeric(buckets.get(0).getStartTime() - 100, scheduleId2, 2.23));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + buckets.getInterval() + 50, scheduleId1,
            4.56));
        data.add(new MeasurementDataNumeric(buckets.get(59).getStartTime() + buckets.getInterval() + 50, scheduleId2,
            4.56));

        WaitForRawInserts waitForRawInserts = new WaitForRawInserts(data.size());

        metricsServer.addNumericData(data, waitForRawInserts);

        waitForRawInserts.await("Failed to insert raw data");

        List<MeasurementDataNumericHighLowComposite> actualData = metricsServer.findDataForGroup(
            asList(scheduleId1, scheduleId2), beginTime.getMillis(), endTime.getMillis(),60);

        assertEquals(actualData.size(), buckets.getNumDataPoints(), "Expected to get back 60 data points.");

        MeasurementDataNumericHighLowComposite expectedBucket0Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(0).getStartTime(), divide(1.1 + 1.2 + 2.1 + 2.2 + 3.1 + 3.2, 6), 3.2, 1.1);
        MeasurementDataNumericHighLowComposite expectedBucket59Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(59).getStartTime(), divide(4.1 + 4.2 + 5.1 + 5.2 + 6.1 + 6.2, 6), 6.2, 4.1);
        MeasurementDataNumericHighLowComposite expectedBucket29Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(29).getStartTime(), Double.NaN, Double.NaN, Double.NaN);

        assertPropertiesMatch("The data for bucket 0 does not match the expected values.", expectedBucket0Data,
            actualData.get(0));
        assertPropertiesMatch("The data for bucket 59 does not match the expected values.", expectedBucket59Data,
            actualData.get(59));
        assertPropertiesMatch("The data for bucket 29 does not match the expected values.", expectedBucket29Data,
            actualData.get(29));
    }

    @Test//(enabled = ENABLED)
    public void find1HourDataComposites() {
        DateTime beginTime = now().minusDays(11);
        DateTime endTime = now();

        Buckets buckets = new Buckets(beginTime, endTime);
        DateTime bucket0Time = new DateTime(buckets.get(0).getStartTime());
        DateTime bucket59Time = new DateTime(buckets.get(59).getStartTime());

        int scheduleId = 123;
        List<AggregateNumericMetric> metrics = asList(
            new AggregateNumericMetric(scheduleId, 2.0, 1.0, 3.0, bucket0Time.getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 4.0, 6.0, bucket0Time.plusHours(1).getMillis()),
            new AggregateNumericMetric(scheduleId, 3.0, 3.0, 3.0, bucket0Time.plusHours(2).getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 2.0, 9.0, bucket59Time.getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 4.0, 6.0, bucket59Time.plusHours(1).getMillis()),
            new AggregateNumericMetric(scheduleId, 3.0, 3.0, 3.0, bucket59Time.plusHours(2).getMillis())
        );
        for (AggregateNumericMetric metric : metrics) {
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MIN, metric.getMin());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MAX, metric.getMax());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.AVG, metric.getAvg());
        }

        List<MeasurementDataNumericHighLowComposite> actualData = Lists.newArrayList(metricsServer.findDataForResource(
            scheduleId, beginTime.getMillis(), endTime.getMillis(),60));

        assertEquals(actualData.size(), buckets.getNumDataPoints(), "Expected to get back 60 data points.");

        MeasurementDataNumericHighLowComposite expectedBucket0Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(0).getStartTime(), divide(2.0 + 5.0 + 3.0, 3), 6.0, 1.0);
        MeasurementDataNumericHighLowComposite expectedBucket59Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(59).getStartTime(), divide(5.0 + 5.0 + 3.0, 3), 9.0, 2.0);
        MeasurementDataNumericHighLowComposite expectedBucket29Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(29).getStartTime(), Double.NaN, Double.NaN, Double.NaN);

        assertPropertiesMatch("The data for bucket 0 does not match the expected values.", expectedBucket0Data,
            actualData.get(0), TEST_PRECISION);
        assertPropertiesMatch("The data for bucket 59 does not match the expected values.", expectedBucket59Data,
            actualData.get(59), TEST_PRECISION);
        assertPropertiesMatch("The data for bucket 29 does not match the expected values.", expectedBucket29Data,
            actualData.get(29), TEST_PRECISION);
    }

    @Test
    public void find1HourDatCompositesForGroup() {
        DateTime beginTime = now().minusDays(11);
        DateTime endTime = now();

        Buckets buckets = new Buckets(beginTime, endTime);
        DateTime bucket0Time = new DateTime(buckets.get(0).getStartTime());
        DateTime bucket59Time = new DateTime(buckets.get(59).getStartTime());

        int scheduleId1 = 123;
        int scheduleId2 = 456;

        List<AggregateNumericMetric> metrics = asList(
            new AggregateNumericMetric(scheduleId1, 1.1, 1.1, 1.1, bucket0Time.getMillis()),
            new AggregateNumericMetric(scheduleId2, 1.2, 1.2, 1.2, bucket0Time.getMillis()),

            new AggregateNumericMetric(scheduleId1, 3.1, 3.1, 3.1, bucket0Time.plusHours(2).getMillis()),
            new AggregateNumericMetric(scheduleId2, 3.2, 3.2, 3.2, bucket0Time.plusHours(2).getMillis()),

            new AggregateNumericMetric(scheduleId1, 4.1, 4.1, 4.1, bucket59Time.getMillis()),
            new AggregateNumericMetric(scheduleId2, 4.2, 4.2, 4.2, bucket59Time.getMillis())
        );
        for (AggregateNumericMetric metric : metrics) {
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MIN, metric.getMin());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MAX, metric.getMax());
            dao.insertOneHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.AVG, metric.getAvg());
        }

        List<MeasurementDataNumericHighLowComposite> actual = metricsServer.findDataForGroup(
            asList(scheduleId1, scheduleId2), beginTime.getMillis(), endTime.getMillis(),60);

        assertEquals(actual.size(), buckets.getNumDataPoints(), "Expected to get back " + buckets.getNumDataPoints() +
            " data points.");

        MeasurementDataNumericHighLowComposite expectedBucket0 = new MeasurementDataNumericHighLowComposite(
            buckets.get(0).getStartTime(), divide(1.1 + 1.2 + 3.1 + 3.2, 4), 3.2, 1.1);
        assertPropertiesMatch("The data for bucket 0 does not match the expected values.", expectedBucket0,
            actual.get(0));

        MeasurementDataNumericHighLowComposite expectedBucket59 = new MeasurementDataNumericHighLowComposite(
            buckets.get(59).getStartTime(), divide(4.1 + 4.2, 2), 4.2, 4.1);
        assertPropertiesMatch("The data for bucket 59 does not match the expected values.", expectedBucket59,
            actual.get(59));
    }

    @Test//(enabled = ENABLED)
    public void find6HourDataComposites() {
        DateTime beginTime = now().minusDays(20);
        DateTime endTime = now();

        Buckets buckets = new Buckets(beginTime, endTime);
        DateTime bucket0Time = new DateTime(buckets.get(0).getStartTime());
        DateTime bucket59Time = new DateTime(buckets.get(59).getStartTime());

        int scheduleId = 123;
        List<AggregateNumericMetric> metrics = asList(
            new AggregateNumericMetric(scheduleId, 2.0, 1.0, 3.0, bucket0Time.getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 4.0, 6.0, bucket0Time.plusHours(1).getMillis()),
            new AggregateNumericMetric(scheduleId, 3.0, 3.0, 3.0, bucket0Time.plusHours(2).getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 2.0, 9.0, bucket59Time.getMillis()),
            new AggregateNumericMetric(scheduleId, 5.0, 4.0, 6.0, bucket59Time.plusHours(1).getMillis()),
            new AggregateNumericMetric(scheduleId, 3.0, 3.0, 3.0, bucket59Time.plusHours(2).getMillis())
        );
        for (AggregateNumericMetric metric : metrics) {
            dao.insertSixHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MIN, metric.getMin());
            dao.insertSixHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.MAX, metric.getMax());
            dao.insertSixHourData(metric.getScheduleId(), metric.getTimestamp(), AggregateType.AVG, metric.getAvg());
        }

        List<MeasurementDataNumericHighLowComposite> actualData = Lists.newArrayList(metricsServer.findDataForResource(
            scheduleId, beginTime.getMillis(), endTime.getMillis(),60));

        assertEquals(actualData.size(), buckets.getNumDataPoints(), "Expected to get back 60 data points.");

        MeasurementDataNumericHighLowComposite expectedBucket0Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(0).getStartTime(), divide(2.0 + 5.0 + 3.0, 3), 6.0, 1.0);
        MeasurementDataNumericHighLowComposite expectedBucket59Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(59).getStartTime(), divide(5.0 + 5.0 + 3.0, 3), 9.0, 2.0);
        MeasurementDataNumericHighLowComposite expectedBucket29Data = new MeasurementDataNumericHighLowComposite(
            buckets.get(29).getStartTime(), Double.NaN, Double.NaN, Double.NaN);

        assertPropertiesMatch("The data for bucket 0 does not match the expected values.", expectedBucket0Data,
            actualData.get(0), TEST_PRECISION);
        assertPropertiesMatch("The data for bucket 59 does not match the expected values.", expectedBucket59Data,
            actualData.get(59), TEST_PRECISION);
        assertPropertiesMatch("The data for bucket 29 does not match the expected values.", expectedBucket29Data,
            actualData.get(29), TEST_PRECISION);
    }

    private void assertColumnMetadataEquals(int scheduleId, DateTime startTime, DateTime endTime, Integer ttl,
        long timestamp) {
        List<RawNumericMetric> metrics = Lists.newArrayList(findRawMetricsWithMetadata(scheduleId, startTime.getMillis(),
            endTime.getMillis()));
        for (RawNumericMetric metric : metrics) {
            assertEquals(metric.getColumnMetadata().getTtl(), ttl, "The TTL does not match the expected value for " +
                metric);
            assertTrue(metric.getColumnMetadata().getWriteTime() >= timestamp, "The column timestamp for " + metric +
                " should be >= " + timestamp + " but it is " + metric.getColumnMetadata().getWriteTime());
        }
    }

    private void assertMetricsIndexEquals(MetricsTable table, long timeSlice, List<MetricsIndexEntry> expected,
        String msg) {
        List<MetricsIndexEntry> actual = Lists.newArrayList(dao.findMetricsIndexEntries(table, timeSlice));
        assertCollectionMatchesNoOrder(msg + ": " + table + " index does not match expected values.",
            expected, actual);
    }

    private void assert1HourDataEquals(int scheduleId, List<AggregateNumericMetric> expected) {
        assertMetricDataEquals(MetricsTable.ONE_HOUR, scheduleId, expected);
    }

    private void assert6HourDataEquals(int scheduleId, List<AggregateNumericMetric> expected) {
        assertMetricDataEquals(MetricsTable.SIX_HOUR, scheduleId, expected);
    }

    private void assert24HourDataEquals(int scheduleId, List<AggregateNumericMetric> expected) {
        assertMetricDataEquals(MetricsTable.TWENTY_FOUR_HOUR, scheduleId, expected);
    }

    private void assertMetricDataEquals(MetricsTable columnFamily, int scheduleId,
        List<AggregateNumericMetric> expected) {
        List<AggregateNumericMetric> actual = Lists.newArrayList(findAggregateMetrics(columnFamily, scheduleId));
        assertCollectionMatchesNoOrder("Metric data for schedule id " + scheduleId + " in table " + columnFamily +
            " does not match expected values", expected, actual, TEST_PRECISION);
    }

    private void assert6HourDataEmpty(int scheduleId) {
        assertMetricDataEmpty(scheduleId, MetricsTable.SIX_HOUR);
    }

    private void assert24HourDataEmpty(int scheduleId) {
        assertMetricDataEmpty(scheduleId, MetricsTable.TWENTY_FOUR_HOUR);
    }

    private void assertMetricDataEmpty(int scheduleId, MetricsTable columnFamily) {
        List<AggregateNumericMetric> metrics = Lists.newArrayList(findAggregateMetrics(columnFamily, scheduleId));
        assertEquals(metrics.size(), 0, "Expected " + columnFamily + " to be empty for schedule id " + scheduleId +
            " but found " + metrics);
    }

    private void assert1HourMetricsIndexEmpty(int scheduleId, long timeSlice) {
        assertMetricsIndexEmpty(scheduleId, MetricsTable.ONE_HOUR, timeSlice);
    }

    private void assert6HourMetricsIndexEmpty(int scheduleId, long timeSlice) {
        assertMetricsIndexEmpty(scheduleId, MetricsTable.SIX_HOUR, timeSlice);
    }

    private void assert24HourMetricsIndexEmpty(int scheduleId, long timeSlice) {
        assertMetricsIndexEmpty(scheduleId, MetricsTable.TWENTY_FOUR_HOUR, timeSlice);
    }

    private void assertMetricsIndexEmpty(int scheduleId, MetricsTable table, long timeSlice) {
        List<MetricsIndexEntry> index = Lists.newArrayList(dao.findMetricsIndexEntries(table, timeSlice));
        assertEquals(index.size(), 0, "Expected metrics index for " + table + " to be empty but found " + index);
    }

    static double divide(double dividend, int divisor) {
        return new BigDecimal(Double.toString(dividend)).divide(new BigDecimal(Integer.toString(divisor)),
            MathContext.DECIMAL64).doubleValue();
    }

    private Iterable<RawNumericMetric> findRawMetricsWithMetadata(int scheduleId, long startTime, long endTime) {
        String cql =
            "SELECT schedule_id, time, value, ttl(value), writetime(value) " +
            "FROM " + MetricsTable.RAW + " " +
            "WHERE schedule_id = " + scheduleId + " AND time >= " + startTime + " AND time < " + endTime;
        return new SimplePagedResult<RawNumericMetric>(cql, new RawNumericMetricMapper(true), storageSession);
    }

}
