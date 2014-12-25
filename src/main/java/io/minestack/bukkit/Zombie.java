package io.minestack.bukkit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.core.DockerClientBuilder;
import com.mongodb.ServerAddress;
import com.rabbitmq.client.Address;
import io.minestack.doublechest.DoubleChest;
import io.minestack.doublechest.model.server.Server;
import org.bson.types.ObjectId;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;

public class Zombie extends JavaPlugin {

    @Override
    public void onEnable() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                getLogger().info("Killing Server");
                getServer().shutdown();
            }
        });

        getLogger().info("Init Mongo Database");
        List<ServerAddress> addresses = new ArrayList<>();
        String mongoAddresses = System.getenv("mongo_addresses");
        for (String mongoAddress : mongoAddresses.split(",")) {
            String[] split = mongoAddress.split(":");
            int port = 27017;
            if (split.length == 2) {
                port = Integer.parseInt(split[1]);
            }
            try {
                addresses.add(new ServerAddress(split[0], port));
            } catch (UnknownHostException e) {
                getLogger().log(Level.SEVERE, "Threw a UnknownHostException in Redstone::main, full stack trace follows: ", e);
            }
        }
        if (System.getenv("mongo_username") == null) {
            DoubleChest.INSTANCE.initMongoDatabase(addresses, System.getenv("mongo_database"));
        } else {
            DoubleChest.INSTANCE.initMongoDatabase(addresses, System.getenv("mongo_username"), System.getenv("mongo_password"), System.getenv("mongo_database"));
        }

        getLogger().info("Init RabbitMQ");
        List<Address> addressList = new ArrayList<>();
        String rabbitAddresses = System.getenv("rabbit_addresses");
        for (String rabbitAddress : rabbitAddresses.split(",")) {
            String[] split = rabbitAddress.split(":");
            int port = 5672;
            if (split.length == 2) {
                port = Integer.parseInt(split[1]);
            }
            addressList.add(new Address(split[0], port));
        }
        DoubleChest.INSTANCE.initRabbitMQDatabase(addressList, System.getenv("rabbit_username"), System.getenv("rabbit_password"));

        if (DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getModel(new ObjectId(System.getenv("server_id"))) == null) {
            getLogger().severe("Could not find server data");
            getServer().shutdown();
            return;
        }

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {

            Server server = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getModel(new ObjectId(System.getenv("server_id")));
            if (server == null) {
                getLogger().severe("Couldn't find server data stopping server");
                getServer().shutdown();
                return;
            }
            if (server.getNetwork() == null) {
                getLogger().severe("Couldn't find network data stopping server");
                getServer().shutdown();
                return;
            }
            if (server.getNode() == null) {
                getLogger().severe("Couldn't find node data stopping server");
                getServer().shutdown();
                return;
            }
            if (server.getServerType() == null) {
                getLogger().severe("Couldn't find type data stopping server");
                getServer().shutdown();
                return;
            }

            if (server.getPort() == -1) {
                DockerClient dockerClient = DockerClientBuilder.getInstance("http://" + server.getNode().getPrivateAddress() + ":4243").build();
                InspectContainerResponse inspectContainerResponse = dockerClient.inspectContainerCmd(server.getContainerId()).exec();
                for (ExposedPort exposedPort : inspectContainerResponse.getNetworkSettings().getPorts().getBindings().keySet()) {
                    if (exposedPort.getPort() == 25565) {
                        int hostPort = inspectContainerResponse.getNetworkSettings().getPorts().getBindings().get(exposedPort)[0].getHostPort();
                        server.setPort(hostPort);
                        break;
                    }
                }
            }

            server.setUpdated_at(new Date(System.currentTimeMillis()));
            DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().saveModel(server);

        }, 200L, 200L);
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        Server server = DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().getModel(new ObjectId(System.getenv("server_id")));
        server.setUpdated_at(new Date(0));
        DoubleChest.INSTANCE.getMongoDatabase().getServerRepository().saveModel(server);
    }

}
