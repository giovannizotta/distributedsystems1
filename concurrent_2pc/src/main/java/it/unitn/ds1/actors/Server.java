package it.unitn.ds1.actors;

import akka.actor.ActorRef;
import akka.actor.Props;
import it.unitn.ds1.Main;
import it.unitn.ds1.messages.CoordinatorServerMessage;
import it.unitn.ds1.messages.Message;
import it.unitn.ds1.resources.Resource;
import it.unitn.ds1.resources.WorkspaceResource;
import it.unitn.ds1.transactions.ServerTransaction;
import it.unitn.ds1.transactions.Transaction;
import it.unitn.ds1.transactions.Workspace;

import java.util.*;


public class Server extends Node {

    //------------------ Definition of server crash phases ------------------
    public enum CrashBefore2PC implements CrashPhase {
        ON_COORD_MSG;

        @Override
        public String toString() {
            return "ServerCrashBefore2PC_" + name();
        }
    }

    public static class CrashDuring2PC {
        public enum CrashDuringVote implements CrashPhase {
            NO_VOTE, AFTER_VOTE;

            @Override
            public String toString() {
                return Server.CrashDuring2PC.name() + "_CrashDuringVote_" + name();
            }
        }

        public enum CrashDuringTermination implements CrashPhase {
            NO_REPLY, RND_REPLY, ALL_REPLY;

            @Override
            public String toString() {
                return Server.CrashDuring2PC.name() + "_CrashDuringTermination_" + name();
            }
        }

        private static String name() {
            return "ServerCrashDuring2PC";
        }
    }

    // initial resource value
    public static final Integer DEFAULT_VALUE = 100;
    // number of resources handled
    public static final Integer DB_SIZE = 10;
    // server db
    private final Map<Integer, Resource> database;
    // server transaction info
    private final Map<Transaction, ServerTransaction> transactionMap = new HashMap<>();
    // resources contained in a transaction for which the server voted commit and not decided yet
    private final Map<Integer, Transaction> pendingResource = new HashMap<>();

    public Server(int id, Set<Node.CrashPhase> crashPhases) {
        super(id, crashPhases);
        database = new HashMap<>();
        for (int i = id * DB_SIZE; i < (id + 1) * DB_SIZE; i++)
            database.put(i, new Resource(DEFAULT_VALUE, 0));
    }

    static public Props props(int id, Set<CrashPhase> crashPhases) {
        return Props.create(Server.class, () -> new Server(id, crashPhases));
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(CoordinatorServerMessage.VoteRequest.class, this::onVoteRequest)
                .match(CoordinatorServerMessage.DecisionRequest.class, this::onDecisionRequest)
                .match(CoordinatorServerMessage.DecisionResponse.class, this::onDecisionResponse)
                .match(CoordinatorServerMessage.TimeoutMsg.class, this::onTimeout)
                .match(CoordinatorServerMessage.RecoveryMsg.class, this::onRecoveryMsg)
                .match(CoordinatorServerMessage.TransactionRead.class, this::onTransactionRead)
                .match(CoordinatorServerMessage.TransactionWrite.class, this::onTransactionWrite)
                .match(Message.CheckCorrectness.class, this::onCheckCorrectness)
                .build();
    }

    // schedule a Timeout message in specified time
    void setTimeout(int time, Transaction transaction) {
        if (Main.SERVER_DEBUG_SET_TIMEOUT)
            print("SET TIMEOUT FOR TXN " + transaction.getTxnId());
        ServerTransaction t = transactionMap.get(transaction);
        t.setTimeout(newTimeout(time, t));
    }

    protected void unsetTimeout(Transaction transaction) {
        ServerTransaction t = transactionMap.get(transaction);
        if (Main.SERVER_DEBUG_UNSET_TIMEOUT)
            print("UNSET TIMEOUT FOR TXN " + t.getTxnId());
        if (t.getTimeout() != null)
            t.getTimeout().cancel();
        t.setTimeout(null);
    }

    protected void sendMessage(ActorRef to, CoordinatorServerMessage msg, Boolean setTimeout) {
        super.sendMessage(to, msg);
        if (setTimeout)
            setTimeout(Main.SERVER_TIMEOUT, msg.transaction);
    }

    protected void reply(CoordinatorServerMessage msg, Boolean setTimeout) {
        sendMessage(getSender(), msg, setTimeout);
    }

    void multicast(CoordinatorServerMessage m, Collection<ActorRef> group, Boolean setTimeout, Class phase) throws CrashException {
        CrashPhase zeroMsg = getZeroMsgCrashPhase(phase);
        CrashPhase rndMsg = getRndMsgCrashPhase(phase);
        CrashPhase allMsg = getAllMsgCrashPhase(phase);

        if (zeroMsg != null)
            maybeCrash(zeroMsg);
        for (ActorRef p : group) {
            if (rndMsg != null)
                maybeCrash(rndMsg);
            sendMessage(p, m);
        }
        if (allMsg != null)
            maybeCrash(allMsg);
        if (setTimeout)
            setTimeout(Main.SERVER_TIMEOUT, m.transaction);
    }

    @Override
    protected boolean hasDecided(Transaction transaction) {
        return transaction2decision.get(transaction) != null;
    } // has the node decided?

    // check if the transaction can be committed
    private Boolean canCommit(Transaction transaction) {
        if (!hasDecided(transaction)) {
            Workspace workspace = transactionMap.get(transaction).getWorkspace();

            for (Map.Entry<Integer, WorkspaceResource> entry : workspace.entrySet()) {
                Integer version = entry.getValue().getVersion();
                Integer key = entry.getKey();
                if (version != database.get(key).getVersion() || pendingResource.containsKey(key)) {
                    return false;
                }
            }
            return true;
        } else { // server has already decided to abort
            return false;
        }
    }

    // free the private workspace of the transaction
    private void freeWorkspace(Transaction transaction) {
        unsetTimeout(transactionMap.get(transaction));
        unlockResources(transaction);
//        transactionMap.remove(transaction);
        pendingTransactions.remove(transaction);
//        transaction2coordinator.remove(transaction);
    }

    // lock the resources for this transaction
    // While resources are locked, no other transaction containing them can
    // be committed, hence the server will vote abort for them
    private void lockResources(Transaction transaction) {
        Workspace workspace = transactionMap.get(transaction).getWorkspace();
        for (Integer key : workspace.keySet()) {
            pendingResource.put(key, transaction);
        }
    }

    // unlocks the resources for this transaction
    private void unlockResources(Transaction transaction) {
        Workspace workspace = transactionMap.get(transaction).getWorkspace();
        for (Integer key : workspace.keySet()) {
            if (pendingResource.get(key) != null && pendingResource.get(key).equals(transaction)) {
                pendingResource.remove(key);
            }
        }
    }

    // updates the 'official' database with the values contained in the private workspace for the transaction
    private void commitWorkspace(Transaction transaction) {
        Workspace workspace = transactionMap.get(transaction).getWorkspace();

        for (Map.Entry<Integer, WorkspaceResource> entry : workspace.entrySet()) {
            Integer key = entry.getKey();
            Integer value = entry.getValue().getValue();
            Integer version = entry.getValue().getVersion();
            Boolean changed = entry.getValue().getChanged();

            assert (version.equals(database.get(key).getVersion()));
            if (changed) {
                database.get(key).setValue(value);
                // increment version number only if the value has been written.
                database.get(key).setVersion(version + 1);
            }
        }
    }

    private void fixDecision(Transaction transaction, CoordinatorServerMessage.Decision d) {
        if (!hasDecided(transaction) && transactionMap.containsKey(transaction)) {
            transaction2decision.put(transaction, d);
            transactionMap.get(transaction).setState(Transaction.State.DECIDED);

            if (Main.SERVER_DEBUG_DECIDED)
                print("DECIDED " + d + " ON TXN " + transaction.getTxnId());
            if (d == CoordinatorServerMessage.Decision.COMMIT) {
                commitWorkspace(transaction);
            }
            freeWorkspace(transaction);
        }

    }

    private void terminationProtocol(Transaction transaction) throws CrashException {
        ServerTransaction t = transactionMap.get(transaction);
        // ask the decision to everyone server of the transaction and the coordinator too
        List<ActorRef> dest = new ArrayList<>(t.getServers());
        dest.add(t.getCoordinator());
        multicast(new CoordinatorServerMessage.DecisionRequest(t), dest, true, CrashDuring2PC.CrashDuringTermination.class);
    }

    // create workspace if the transaction is new, copy the resource in private workspace if
    // it is the first time it is accessed
    private WorkspaceResource processWorkspace(CoordinatorServerMessage.TransactionAction msg) {
        // create workspace if the transaction is new
        if (!transactionMap.containsKey(msg.transaction)) {
            Integer clientId = msg.transaction.getClientId();
            Integer clientAttemptedTxn = msg.transaction.getNumAttemptedTxn();
            ServerTransaction transaction = new ServerTransaction(clientId, clientAttemptedTxn, getSender());
            transactionMap.put(msg.transaction, transaction);
            pendingTransactions.add(transaction);
        }

        ServerTransaction transaction = transactionMap.get(msg.transaction);
        if (!transaction.getWorkspace().containsKey(msg.key)) {
            Resource r = (Resource) database.get(msg.key).clone();
            transaction.getWorkspace().put(msg.key, new WorkspaceResource(r, false));
        }

        return transaction.getWorkspace().get(msg.key);
    }

    public void onTransactionRead(CoordinatorServerMessage.TransactionRead msg) {
        try {
            maybeCrash(CrashBefore2PC.ON_COORD_MSG);
            int valueRead = processWorkspace(msg).getValue();
            if (Main.SERVER_DEBUG_READ)
                print("READ OPERATION ON KEY " + valueRead + " FOR TXN " + msg.transaction.getTxnId());
            reply(new CoordinatorServerMessage.TxnReadResponseMsg(msg.transaction, msg.key, valueRead));
        } catch (CrashException e) {
        }
    }

    public void onTransactionWrite(CoordinatorServerMessage.TransactionWrite msg) {
        WorkspaceResource resource = processWorkspace(msg);
        resource.setValue(msg.value);
        resource.setChanged(true);
        try {
            maybeCrash(CrashBefore2PC.ON_COORD_MSG);
        } catch (CrashException e) {
        }
    }

    public void onVoteRequest(CoordinatorServerMessage.VoteRequest msg) {
        Transaction transaction = msg.transaction;
        CoordinatorServerMessage.Vote vote = null;

        //if (id==2) {crash(5000); return;}    // simulate a crash
        //if (id==2) delay(4000);              // simulate a delay
        if (!canCommit(transaction)) {
            fixDecision(transaction, CoordinatorServerMessage.Decision.ABORT);
            vote = CoordinatorServerMessage.Vote.NO;
        } else { // vote commit
            lockResources(transaction);
            transactionMap.get(msg.transaction).setState(Transaction.State.READY);
            transactionMap.get(msg.transaction).setServers(msg.servers);
            vote = CoordinatorServerMessage.Vote.YES;
        }
        if (Main.SERVER_DEBUG_SEND_VOTE)
            print("SENDING VOTE " + vote);
        try {
            // tell the decision to coordinator
            maybeCrash(CrashDuring2PC.CrashDuringVote.NO_VOTE);
            reply(new CoordinatorServerMessage.VoteResponse(transaction, vote), true);
            maybeCrash(CrashDuring2PC.CrashDuringVote.AFTER_VOTE);
        } catch (CrashException e) {
        }
    }

    public void onTimeout(CoordinatorServerMessage.TimeoutMsg msg) {
        if (!hasDecided(msg.transaction)) {
            if (Main.SERVER_DEBUG_TIMEOUT)
                print("TIMEOUT FOR TXN " + msg.transaction.getTxnId());
            ServerTransaction t = transactionMap.get(msg.transaction);
            assert t.getState() != Transaction.State.DECIDED;
            if (t.getState() == Transaction.State.INIT) // this should never happen since we do not set timeouts before 2pc
                fixDecision(msg.transaction, CoordinatorServerMessage.Decision.ABORT);
            else {
                // if voted commit do termination protocol:
                // ask decision to coordinator and fellow servers
                try {
                    terminationProtocol(t);
                } catch (CrashException e) {
                }
            }
        }
    }

    @Override
    public void onRecoveryMsg(CoordinatorServerMessage.RecoveryMsg msg) {
        getContext().become(createReceive());

        for (Transaction t : new HashSet<>(pendingTransactions)) {
            // decide to abort every transaction for which we have not voted yet
            if (transactionMap.get(t).getState() == Transaction.State.INIT)
                fixDecision(t, CoordinatorServerMessage.Decision.ABORT);
            else { // it is in READY
                try {
                    terminationProtocol(t);
                } catch (CrashException e) {
                }
            }
        }

        if (Main.SERVER_DEBUG_RECOVERY) {
            print("RECOVERED");
//            print("KEYSET: " + pendingResource.keySet().toString());
//            print("PENDING TXN: " + pendingTransactions.toString());
        }
    }

    public void onDecisionResponse(CoordinatorServerMessage.DecisionResponse msg) { /* Decision Response */
        Transaction transaction = msg.transaction;
        // store the decision
        fixDecision(transaction, msg.decision);
    }


    @Override
    public void onCheckCorrectness(Message.CheckCorrectness msg) {
        Integer result = 0;
        for (Integer key : database.keySet()) {
            result += database.get(key).getValue();
        }
        reply(new Message.CheckCorrectnessResponse(id, result, numCrashes));
        getContext().stop(getSelf());
    }
}