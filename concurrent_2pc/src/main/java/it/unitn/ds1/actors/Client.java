package it.unitn.ds1.actors;

import java.util.*;
import java.util.concurrent.TimeUnit;

import akka.actor.*;
import it.unitn.ds1.messages.ClientCoordinatorMessages;
import it.unitn.ds1.messages.Message;
import scala.concurrent.duration.Duration;

public class Client extends AbstractActor {
    private static final double COMMIT_PROBABILITY = 0.8;
    private static final double WRITE_PROBABILITY = 0.5;
    private static final int MIN_TXN_LENGTH = 2;
    private static final int MAX_TXN_LENGTH = 4;
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
    private Cancellable acceptTimeout;
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

    /*-- Actor methods -------------------------------------------------------- */

    // start a new TXN: choose a random coordinator, send TxnBeginMsg and set timeout
    void beginTxn() {

        // some delay between transactions from the same client
        try { Thread.sleep(10); }
        catch (InterruptedException e) { e.printStackTrace(); }

        acceptedTxn = false;
        numAttemptedTxn++;

        // contact a random coordinator and begin TXN
        currentCoordinator = coordinators.get(r.nextInt(coordinators.size()));
        currentCoordinator.tell(new ClientCoordinatorMessages.TxnBeginMsg(clientId, numAttemptedTxn), getSelf());

        // how many operations (taking some amount and adding it somewhere else)?
        int numExtraOp = RAND_LENGTH_RANGE > 0 ? r.nextInt(RAND_LENGTH_RANGE) : 0;
        numOpTotal = MIN_TXN_LENGTH + numExtraOp;
        numOpDone = 0;

        // timeout for confirmation of TXN by the coordinator (sent to self)
        acceptTimeout = getContext().system().scheduler().scheduleOnce(
                Duration.create(500, TimeUnit.MILLISECONDS),
                getSelf(),
                new ClientCoordinatorMessages.TxnAcceptTimeoutMsg(), // message sent to myself
                getContext().system().dispatcher(), getSelf()
        );
        System.out.println("CLIENT " + clientId + " BEGIN");
    }

    // end the current TXN sending TxnEndMsg to the coordinator
    void endTxn() {
        boolean doCommit = r.nextDouble() < COMMIT_PROBABILITY;
        currentCoordinator.tell(new ClientCoordinatorMessages.TxnEndMsg(clientId, doCommit), getSelf());
        firstValue = null;
        secondValue = null;
        System.out.println("CLIENT " + clientId + " END");
    }

    // READ two items (will move some amount from the value of the first to the second)
    void readTwo() {
        // read two different keys
        firstKey = r.nextInt(maxKey + 1);
        int randKeyOffset = 1 + r.nextInt(maxKey - 1);
        secondKey = (firstKey + randKeyOffset) % (maxKey + 1);

        // READ requests
        currentCoordinator.tell(new ClientCoordinatorMessages.ReadMsg(clientId, firstKey), getSelf());
        currentCoordinator.tell(new ClientCoordinatorMessages.ReadMsg(clientId, secondKey), getSelf());

        // delete the current read values
        firstValue = null;
        secondValue = null;

        System.out.println("CLIENT " + clientId + " READ #"+ numOpDone + " (" + firstKey + "), (" + secondKey + ")");
    }

    // WRITE two items (called with probability WRITE_PROBABILITY after readTwo() values are returned)
    void writeTwo() {

        // take some amount from one value and pass it to the other, then request writes
        Integer amountTaken = 0;
        if(firstValue >= 1) amountTaken = 1 + r.nextInt(firstValue);
        currentCoordinator.tell(new ClientCoordinatorMessages.WriteMsg(clientId, firstKey, firstValue - amountTaken), getSelf());
        currentCoordinator.tell(new ClientCoordinatorMessages.WriteMsg(clientId, secondKey, secondValue + amountTaken), getSelf());
        System.out.println("CLIENT " + clientId + " WRITE #"+ numOpDone
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

    private void onStopMsg(ClientCoordinatorMessages.StopMsg msg) {
        getContext().stop(getSelf());
    }

    private void onTxnAcceptMsg(ClientCoordinatorMessages.TxnAcceptMsg msg) {
        acceptedTxn = true;
        acceptTimeout.cancel();
        readTwo();
    }

    private void onTxnAcceptTimeoutMsg(ClientCoordinatorMessages.TxnAcceptTimeoutMsg msg) throws InterruptedException {
        if(!acceptedTxn) beginTxn();
    }

    private void onReadResultMsg(ClientCoordinatorMessages.ReadResultMsg msg) {
        System.out.println("CLIENT " + clientId + " READ RESULT (" + msg.key + ", " + msg.value + ")");

        // save the read value(s)
        if(msg.key.equals(firstKey)) firstValue = msg.value;
        if(msg.key.equals(secondKey)) secondValue = msg.value;

        boolean opDone = (firstValue != null && secondValue != null);

        // do we only read or also write?
        double writeRandom = r.nextDouble();
        boolean doWrite = writeRandom < WRITE_PROBABILITY;
        if(doWrite && opDone) writeTwo();

        // check if the transaction should end;
        // otherwise, read two again
        if(opDone) numOpDone++;
        if(numOpDone >= numOpTotal) {
            endTxn();
        } else if(opDone) {
            readTwo();
        }
    }

    private void onTxnResultMsg(ClientCoordinatorMessages.TxnResultMsg msg) throws InterruptedException {
        if(msg.commit) {
            numCommittedTxn++;
            System.out.println("CLIENT " + clientId + " COMMIT OK ("+numCommittedTxn+"/"+numAttemptedTxn+")");
        }
        else {
            System.out.println("CLIENT " + clientId + " COMMIT FAIL ("+(numAttemptedTxn - numCommittedTxn)+"/"+numAttemptedTxn+")");
        }
        beginTxn();
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(Message.WelcomeMsg.class,  this::onWelcomeMsg)
                .match(ClientCoordinatorMessages.TxnAcceptMsg.class,  this::onTxnAcceptMsg)
                .match(ClientCoordinatorMessages.TxnAcceptTimeoutMsg.class,  this::onTxnAcceptTimeoutMsg)
                .match(ClientCoordinatorMessages.ReadResultMsg.class,  this::onReadResultMsg)
                .match(ClientCoordinatorMessages.TxnResultMsg.class,  this::onTxnResultMsg)
                .match(ClientCoordinatorMessages.StopMsg.class,  this::onStopMsg)
                .build();
    }
}