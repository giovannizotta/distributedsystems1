package it.unitn.ds1.actors;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import it.unitn.ds1.Main;
import it.unitn.ds1.messages.ClientCoordinatorMessage;
import it.unitn.ds1.messages.Message;
import it.unitn.ds1.messages.TimeoutMessages;
import scala.concurrent.duration.Duration;

import java.io.Serializable;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Client extends AbstractActor {
    // transactions parameters
    private static final double COMMIT_PROBABILITY = 1;
    private static final double WRITE_PROBABILITY = 0.5;
    private static final int MIN_TXN_LENGTH = 20;
    private static final int MAX_TXN_LENGTH = 40;
    private static final int RAND_LENGTH_RANGE = MAX_TXN_LENGTH - MIN_TXN_LENGTH + 1;

    private final Integer clientId;
    private List<ActorRef> coordinators;

    // the maximum key associated to items of the store
    private Integer maxKey;

    // keep track of the number of TXNs (attempted, successfully committed)
    private Integer numAttemptedTxn;
    private Integer numCommittedTxn;

    // TXN operation (move some amount from a value to another)
    private Boolean acceptedTxn;
    private ActorRef currentCoordinator;
    private Integer firstKey, secondKey;
    private Integer firstValue, secondValue;
    private Integer numOpTotal;
    private Integer numOpDone;
    private Cancellable acceptTimeout, operationTimeout;
    private final Random r;

    /*-- Actor constructor ---------------------------------------------------- */

    public Client(int clientId) {
        this.clientId = clientId;
        this.numAttemptedTxn = 0;
        this.numCommittedTxn = 0;
        this.r = new Random();
    }

    static public Props props(int clientId) {
        return Props.create(Client.class, () -> new Client(clientId));
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Message.WelcomeMsg.class, this::onWelcomeMsg)
                .match(ClientCoordinatorMessage.TxnAcceptMsg.class, this::onTxnAcceptMsg)
                .match(ClientCoordinatorMessage.ReadResultMsg.class, this::onReadResultMsg)
                .match(ClientCoordinatorMessage.TxnResultMsg.class, this::onTxnResultMsg)
                .match(ClientCoordinatorMessage.StopMsg.class, this::onStopMsg)
                .match(TimeoutMessages.Client.TxnAcceptMsg.class, this::onTxnAcceptTimeoutMsg)
                .match(TimeoutMessages.Client.TxnOperationMsg.class, this::onTxnOperationTimeoutMsg)
                .build();
    }

    private Cancellable setTimeout(Serializable msg) {
        // set a timeout after some time
        return getContext().system().scheduler().scheduleOnce(
                Duration.create(Main.CLIENT_TIMEOUT, TimeUnit.MILLISECONDS),
                getSelf(),
                msg, // message sent to myself
                getContext().system().dispatcher(), getSelf()
        );
    }

    private void unsetTimeouts() {
        if (operationTimeout != null)
            operationTimeout.cancel();
        if (acceptTimeout != null)
            acceptTimeout.cancel();
        acceptTimeout = null;
        operationTimeout = null;
    }

    /*-- Actor methods -------------------------------------------------------- */

    // start a new TXN: choose a random coordinator, send TxnBeginMsg and set timeout
    void beginTxn() {

        // some delay between transactions from the same client
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        unsetTimeouts();

        acceptedTxn = false;
        numAttemptedTxn++;

        // contact a random coordinator and begin TXN
        currentCoordinator = coordinators.get(r.nextInt(coordinators.size()));
        currentCoordinator.tell(new ClientCoordinatorMessage.TxnBeginMsg(clientId, numAttemptedTxn), getSelf());

        // how many operations (taking some amount and adding it somewhere else)?
        int numExtraOp = RAND_LENGTH_RANGE > 0 ? r.nextInt(RAND_LENGTH_RANGE) : 0;
        numOpTotal = MIN_TXN_LENGTH + numExtraOp;
        numOpDone = 0;

        // timeout for confirmation of TXN by the coordinator (sent to self)
        acceptTimeout = setTimeout(new TimeoutMessages.Client.TxnAcceptMsg());
        if (Main.CLIENT_DEBUG_BEGIN_TXN)
            print("BEGIN");
    }

    // end the current TXN sending TxnEndMsg to the coordinator
    void endTxn() {
        boolean doCommit = r.nextDouble() < COMMIT_PROBABILITY;
        currentCoordinator.tell(new ClientCoordinatorMessage.TxnEndMsg(clientId, numAttemptedTxn, doCommit), getSelf());
        operationTimeout = setTimeout(new TimeoutMessages.Client.TxnOperationMsg());

        firstValue = null;
        secondValue = null;
        if (Main.CLIENT_DEBUG_END_TXN)
            print("END");
    }

    // READ two items (will move some amount from the value of the first to the second)
    void readTwo() {
        // read two different keys
        firstKey = r.nextInt(maxKey + 1);
        int randKeyOffset = 1 + r.nextInt(maxKey - 1);
        secondKey = (firstKey + randKeyOffset) % (maxKey + 1);

        // READ requests
        currentCoordinator.tell(new ClientCoordinatorMessage.ReadMsg(clientId, numAttemptedTxn, firstKey), getSelf());
        currentCoordinator.tell(new ClientCoordinatorMessage.ReadMsg(clientId, numAttemptedTxn, secondKey), getSelf());

        operationTimeout = setTimeout(new TimeoutMessages.Client.TxnOperationMsg());
        // delete the current read values
        firstValue = null;
        secondValue = null;
        if (Main.CLIENT_DEBUG_READ_TXN)
            print("READ #" + numOpDone + " (" + firstKey + "), (" + secondKey + ")");
    }

    // WRITE two items (called with probability WRITE_PROBABILITY after readTwo() values are returned)
    void writeTwo() {

        // take some amount from one value and pass it to the other, then request writes
        Integer amountTaken = 0;
        if (firstValue >= 1) amountTaken = 1 + r.nextInt(firstValue);
        currentCoordinator.tell(new ClientCoordinatorMessage.WriteMsg(clientId, numAttemptedTxn, firstKey, firstValue - amountTaken), getSelf());
        currentCoordinator.tell(new ClientCoordinatorMessage.WriteMsg(clientId, numAttemptedTxn, secondKey, secondValue + amountTaken), getSelf());

        if (Main.CLIENT_DEBUG_WRITE_TXN)
            print("WRITE #" + numOpDone
                    + " taken " + amountTaken
                    + " (" + firstKey + ", " + (firstValue - amountTaken) + "), ("
                    + secondKey + ", " + (secondValue + amountTaken) + ")");
    }

    /*-- Message handlers ----------------------------------------------------- */

    private void onWelcomeMsg(Message.WelcomeMsg msg) {
        this.coordinators = msg.group;
        System.out.println(coordinators);
        this.maxKey = msg.maxKey;
        beginTxn();
    }

    private void onStopMsg(Message.StopMsg msg) {
        print("SUCCESSFUL COMMITS: ("
                + numCommittedTxn + "/" + numAttemptedTxn + ")");
        getContext().stop(getSelf());
    }

    private void onTxnAcceptMsg(ClientCoordinatorMessage.TxnAcceptMsg msg) {
        acceptedTxn = true;
        unsetTimeouts();
        readTwo();
    }


    private void onReadResultMsg(ClientCoordinatorMessage.ReadResultMsg msg) {
        if (Main.CLIENT_DEBUG_READ_RESULT)
            print("READ RESULT (" + msg.key + ", " + msg.value + ")");

        // save the read value(s)
        if (msg.key.equals(firstKey)) firstValue = msg.value;
        if (msg.key.equals(secondKey)) secondValue = msg.value;

        boolean opDone = (firstValue != null && secondValue != null);
        if (opDone) unsetTimeouts();

        // do we only read or also write?
        double writeRandom = r.nextDouble();
        boolean doWrite = writeRandom < WRITE_PROBABILITY;
        if (doWrite && opDone) writeTwo();

        // check if the transaction should end;
        // otherwise, read two again
        if (opDone) numOpDone++;
        if (numOpDone >= numOpTotal) {
            endTxn();
        } else if (opDone) {
            readTwo();
        }
    }

    private void onTxnResultMsg(ClientCoordinatorMessage.TxnResultMsg msg) {
        if (msg.commit) {
            numCommittedTxn++;
            if (Main.CLIENT_DEBUG_COMMIT_OK)
                print("COMMIT OK (" + numCommittedTxn + "/" + numAttemptedTxn + ")");
        } else {
            if (Main.CLIENT_DEBUG_COMMIT_KO)
                print("COMMIT FAIL (" + (numAttemptedTxn - numCommittedTxn) + "/" + numAttemptedTxn + ")");
        }
        // consider the message only if it answers to the current transaction
        if (msg.numAttemptedTxn.equals(numAttemptedTxn)) {
            unsetTimeouts();
            beginTxn();
        }
    }

    private void onTxnAcceptTimeoutMsg(TimeoutMessages.Client.TxnAcceptMsg msg) throws InterruptedException {
        if (!acceptedTxn) {
            if (Main.CLIENT_DEBUG_TIMEOUT_TXN_ACCEPT)
                print("TIMEOUT DURING ACCEPT, ABORTING CURRENT TRANSACTION");
            beginTxn();
        }
    }

    private void onTxnOperationTimeoutMsg(TimeoutMessages.Client.TxnOperationMsg msg) throws InterruptedException {
        // begin a new transaction if the coordinator is not responding
        if (Main.CLIENT_DEBUG_TIMEOUT_TXN_OPERATION)
            print("TIMEOUT DURING OPERATION, ABORTING CURRENT TRANSACTION");
        beginTxn();
    }

    private void print(String msg) {
        System.out.format("Client      %2d: %s\n", clientId, msg);
    }
}
