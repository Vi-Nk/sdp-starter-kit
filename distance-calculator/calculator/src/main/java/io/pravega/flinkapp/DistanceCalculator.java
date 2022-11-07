package io.pravega.flinkapp;


import io.pravega.client.stream.Stream;
import io.pravega.connectors.flink.FlinkPravegaReader;
import io.pravega.connectors.flink.PravegaConfig;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.runtime.util.bash.FlinkConfigLoader;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor;
import org.apache.flink.streaming.api.functions.AssignerWithPeriodicWatermarks;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

import org.apache.flink.util.Collector;
import org.apache.flink.api.java.utils.ParameterTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class DistanceCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(DistanceCalculator.class);
    public static void main(String[] args) throws Exception{

        //ParameterTool params = ParameterTool.fromArgs(args);
        // initialize the parameter utility tool in order to retrieve input parameters
        final String scope = getEnvVar("PRAVEGA_SCOPE", "distancecalc");
        final String streamName = getEnvVar("PRAVEGA_STREAM", "distance-calculator");
        final URI controllerURI = URI.create(getEnvVar("PRAVEGA_CONTROLLER_URI", "tcp://127.0.0.1:9090"));
        LOG.info("PRAVEGA_CONTROLLER_URI:" + controllerURI );
        LOG.info("PRAVEGA_SCOPE:" + scope );
        LOG.info("PRAVEGA_STREAM:" + streamName );

        PravegaConfig pravegaConfig = PravegaConfig.fromDefaults()
                .withControllerURI(controllerURI)
                .withDefaultScope(scope)
                //Enable it if with Nautilus
                //.withCredentials(credentials)
                .withHostnameValidation(false);

        // create the Pravega input stream (if necessary)
        Stream stream = Utils.createStream(
                pravegaConfig,
                streamName);


        // initialize the Flink execution environment

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // create the Pravega source to read a stream of text
        FlinkPravegaReader<RawSenorData> source = FlinkPravegaReader.<RawSenorData>builder()
                .withPravegaConfig(pravegaConfig)
                .forStream(stream, Utils.getTailStreamCut(pravegaConfig, streamName))
                .withDeserializationSchema(new JsonDeserializationSchema<>(RawSenorData.class))
                .build();

        // count each word over a 10 second time period
        DataStream<OutSenorData> dataStream = env.addSource(source).name(streamName)
                //.flatMap(new DataAnalyzer.Splitter())
                //.flatMap(new Splitter())
                /*.assignTimestampsAndWatermarks(
                        new BoundedOutOfOrdernessTimestampExtractor<RawSenorData>(Time.milliseconds(1000)) {
                         @Override
                        public long extractTimestamp(RawSenorData element) {
                             Utils.timeformat(element.getTimestamp());
                             return element.getTimestamp();
                         }
                })*/
                .setParallelism(1)
                .assignTimestampsAndWatermarks(new MyAssignTime())
                .keyBy(
                        new KeySelector<RawSenorData, String>() {
                            @Override
                            public String getKey(RawSenorData d) throws Exception {
                                return d.getId();
                            }
                        }
                )
                .window(TumblingEventTimeWindows.of(Time.milliseconds(3000)))
                .aggregate(new MyAgg(), new MyPro());

        // create an output sink to print to stdout for verification
        dataStream.print();
        // create an sink to InfluxDB
        dataStream.addSink(new InfluxdbSink());
        // execute within the Flink environment
        env.execute("DistanceCalculator");
    }


    public static class MyAssignTime implements AssignerWithPeriodicWatermarks<RawSenorData> {

        private final long maxOutOfOrderness = 2000; // 2 seconds

        private long currentMaxTimestamp;

        @Override
        public long extractTimestamp(RawSenorData element, long previousElementTimestamp) {
            long timestamp = element.getTimestamp();
            currentMaxTimestamp = Math.max(timestamp, currentMaxTimestamp);
            //System.out.println("timestamp: " + Utils.timeformat(timestamp) + "|" + timestamp);
            //System.out.println("previousElementTimestamp: " + previousElementTimestamp);
            System.out.println("currentMaxTimestamp: " + Utils.timeformat(currentMaxTimestamp) + "|" + currentMaxTimestamp);
            return timestamp;
        }

        @Override
        public Watermark getCurrentWatermark() {
            // return the watermark as current highest timestamp minus the out-of-orderness bound
            Watermark a = new Watermark(currentMaxTimestamp - maxOutOfOrderness);
            System.out.println("Watermark: " + a.toString());
            return a;
        }
    }

    private static class MyPro extends ProcessWindowFunction<Double, OutSenorData, String, TimeWindow> {

         public void process(String key, Context context, Iterable<Double> elements, Collector<OutSenorData> out) throws Exception {
             for (Double d: elements) {
                 int trend = 0;
                 Double diff = 0.0;
                 //Trend Meaning:
                 // 0: Normal, 2: A little Far, 3: Far
                 if (d <= 5)  {
                     trend = 0;
                 }
                 else if (d > 5 && d <= 6 ) {
                     trend = 2;
                 }
                 else {
                     trend = 3;
                 }
                 out.collect(new OutSenorData(context.window().getEnd(), key, diff, trend, d));
           }
        }
    }

    public static class AverageAccumulator{
        int count;
        Double sum;

        public AverageAccumulator() {}
        public AverageAccumulator(int count, Double sum) {
            this.count = count;
            this.sum = sum;
        }
    }

    private static class MyAgg implements AggregateFunction<RawSenorData, AverageAccumulator, Double> {

        @Override
        public AverageAccumulator createAccumulator() {
            return new AverageAccumulator(0, 0.0);
        }

        @Override
        public AverageAccumulator merge(AverageAccumulator a, AverageAccumulator b) {
            a.count += b.count;
            a.sum += b.sum;
            return a;
        }

        @Override
        public AverageAccumulator add(RawSenorData value, AverageAccumulator acc) {
            acc.count ++;
            acc.sum += value.getValue();
            System.out.println("count: " + acc.count + " sum: " + acc.sum);
            return acc;
        }

        @Override
        public Double getResult(AverageAccumulator acc) {
            return acc.sum / (double) acc.count;
        }
    }
    private static String getEnvVar(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        return value;
    }
}

