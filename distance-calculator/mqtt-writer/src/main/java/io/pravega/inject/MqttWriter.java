package io.pravega.inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.fusesource.mqtt.client.BlockingConnection;
import org.fusesource.mqtt.client.MQTT;
import org.fusesource.mqtt.client.QoS;
import org.fusesource.mqtt.client.Topic;

public class MqttWriter {

    public static void main(String[] args) throws Exception {
        ObjectNode message = null;
        System.out.println("Connecting to MQTT Brocker");
        MQTT mqtt = new MQTT();
        mqtt.setHost(CommonParams.getBrokerUrl());
        BlockingConnection connection = mqtt.blockingConnection();
        connection.connect();

        System.out.println("MQTT_BROKER_URL: " + CommonParams.getBrokerUrl());
        System.out.println("MQTT_TOPIC: " + CommonParams.getTopic());
        System.out.println("Connected to mqtt server");
        // Subscribe to  fidelityAds topic
        Topic[] topics = { new Topic(CommonParams.getTopic(), QoS.AT_LEAST_ONCE)};
        connection.subscribe(topics);

        //  Coverst CSV  data to JSON
        String data = DataGenerator.convertCsvToJson(CommonParams.getDataFile());
        // Deserialize the JSON message.
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonArray = objectMapper.readTree(data);
        if (jsonArray.isArray()) {
            for (JsonNode node : jsonArray) {
                message = (ObjectNode) node;
                connection.publish(CommonParams.getTopic(), message.toString().getBytes(), QoS.EXACTLY_ONCE, false);
                System.out.println(message);
                Thread.sleep(1000);
            }
        }
    }
}
