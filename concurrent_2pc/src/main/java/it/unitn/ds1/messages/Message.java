package it.unitn.ds1.messages;

import akka.actor.ActorRef;
import it.unitn.ds1.actors.Node;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class Message {

    // send this message to the client at startup to inform it about the group and the keys
    public static class WelcomeMsg implements Serializable {
        public final Integer maxKey;
        public final List<ActorRef> group;
        public WelcomeMsg(int maxKey, List<ActorRef> group) {
            this.maxKey = maxKey;
            this.group = Collections.unmodifiableList(new ArrayList<>(group));
        }
    }

    public static class CheckerWelcomeMsg implements Serializable {
        public final Integer maxKey;
        public final List<ActorRef> servers;
        public final List<ActorRef> coordinators;

        public CheckerWelcomeMsg(Integer maxKey, List<ActorRef> servers, List<ActorRef> coordinators) {
            this.maxKey = maxKey;
            this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
            this.coordinators = Collections.unmodifiableList(new ArrayList<>(coordinators));
        }
    }

    public static class CheckCorrectness implements Serializable {
    }

    public static class CheckCorrectnessResponse implements Serializable {
        public final Integer id;
        public final Integer sumOfKeys;
        public final Node.CrashPhaseMap numCrashes;

        public CheckCorrectnessResponse(Integer id, Integer sumOfKeys, Node.CrashPhaseMap numCrashes) {
            this.id = id;
            this.sumOfKeys = sumOfKeys;
            this.numCrashes = numCrashes;
        }
    }

    public static class CheckerMsg implements Serializable {
        public final ActorRef checker;

        public CheckerMsg(ActorRef checker) {
            this.checker = checker;
        }
    }

    public static class StopMsg implements Serializable {
    }
}
