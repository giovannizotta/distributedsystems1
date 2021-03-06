package it.unitn.ds1.messages;

import akka.actor.ActorRef;
import it.unitn.ds1.transactions.Transaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public abstract class CoordinatorServerMessage extends Message {

    public enum Vote {NO, YES}

    public enum Decision {ABORT, COMMIT}

    public final Transaction transaction;


    public CoordinatorServerMessage(Transaction transaction) {
        this.transaction = new Transaction.UnmodifiableTransaction(transaction);
    }

    public static class VoteRequest extends CoordinatorServerMessage {
        public final List<ActorRef> servers;

        public VoteRequest(Transaction transaction, Set<ActorRef> servers) {
            super(transaction);
            this.servers = Collections.unmodifiableList(new ArrayList<>(servers));
        }
    }

    public static class VoteResponse extends CoordinatorServerMessage {
        public final Vote vote;

        public VoteResponse(Transaction transaction, Vote vote) {
            super(transaction);
            this.vote = vote;
        }
    }

    public static class DecisionRequest extends CoordinatorServerMessage {
        public DecisionRequest(Transaction transaction) {
            super(transaction);
        }
    }

    public static class DecisionResponse extends CoordinatorServerMessage {

        public final Decision decision;

        public DecisionResponse(Transaction transaction, Decision decision) {
            super(transaction);
            this.decision = decision;
        }

    }

    /*-- Message classes ------------------------------------------------------ */
    public static abstract class TransactionAction extends CoordinatorServerMessage {

        public final Integer key;

        public TransactionAction(Transaction transaction, Integer key) {
            super(transaction);
            this.key = key;
        }
    }

    public static class TransactionRead extends TransactionAction {
        public TransactionRead(Transaction transaction, Integer key) {
            super(transaction, key);
        }
    }


    public static class TransactionWrite extends TransactionAction {
        public final Integer value;

        public TransactionWrite(Transaction transaction, Integer key, Integer value) {
            super(transaction, key);
            this.value = value;
        }
    }

    public static class TxnReadResponseMsg extends TransactionAction {
        public final Integer valueRead;

        public TxnReadResponseMsg(Transaction transaction, Integer key, Integer valueRead) {
            super(transaction, key);
            this.valueRead = valueRead;
        }
    }

    public static class TimeoutMsg extends CoordinatorServerMessage {
        public TimeoutMsg(Transaction transaction) {
            super(transaction);
        }
    }

    public static class RecoveryMsg extends Message {
    }
}
