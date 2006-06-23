// Prevayler, The Free-Software Prevalence Layer
// Copyright 2001-2006 by Klaus Wuestefeld
//
// This library is distributed in the hope that it will be useful, but WITHOUT
// ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
// FITNESS FOR A PARTICULAR PURPOSE.
//
// Prevayler is a trademark of Klaus Wuestefeld.
// See the LICENSE file for license details.

package org.prevayler.implementation.publishing;

import org.prevayler.Clock;
import org.prevayler.foundation.Cool;
import org.prevayler.foundation.Turn;
import org.prevayler.implementation.Capsule;
import org.prevayler.implementation.TransactionGuide;
import org.prevayler.implementation.TransactionTimestamp;
import org.prevayler.implementation.clock.PausableClock;
import org.prevayler.implementation.journal.Journal;
import org.prevayler.implementation.publishing.censorship.TransactionCensor;

public class CentralPublisher<T> extends AbstractPublisher<T> {

    private final PausableClock _pausableClock;

    private final TransactionCensor<T> _censor;

    private final Journal<T> _journal;

    private volatile int _pendingPublications = 0;

    private final Object _pendingPublicationsMonitor = new Object();

    private Turn _nextTurn = Turn.first();

    private long _nextTransaction;

    private final Object _nextTurnMonitor = new Object();

    public CentralPublisher(Clock clock, TransactionCensor<T> censor, Journal<T> journal) {
        super(new PausableClock(clock));
        _pausableClock = (PausableClock) _clock; // This is just to avoid
        // casting the inherited
        // _clock every time.

        _censor = censor;
        _journal = journal;
    }

    public <R, E extends Exception> void publish(Capsule<T, R, E> capsule) {
        synchronized (_pendingPublicationsMonitor) {
            if (_pendingPublications == 0)
                _pausableClock.pause();
            _pendingPublications++;
        }

        try {
            publishWithoutWorryingAboutNewSubscriptions(capsule);
        } finally {
            synchronized (_pendingPublicationsMonitor) {
                _pendingPublications--;
                if (_pendingPublications == 0) {
                    _pausableClock.resume();
                    _pendingPublicationsMonitor.notifyAll();
                }
            }
        }
    }

    private <R, E extends Exception> void publishWithoutWorryingAboutNewSubscriptions(Capsule<T, R, E> capsule) {
        TransactionGuide<T, R, E> guide = approve(capsule);
        if (guide != null) {
            _journal.append(guide);
            notifySubscribers(guide);
        }
    }

    private <R, E extends Exception> TransactionGuide<T, R, E> approve(Capsule<T, R, E> capsule) {
        synchronized (_nextTurnMonitor) {
            TransactionTimestamp<T, R, E> timestamp = new TransactionTimestamp<T, R, E>(capsule, _nextTransaction, _pausableClock.realTime());

            if (!_censor.approve(timestamp)) {
                return null;
            }

            // Only count this transaction once approved.
            Turn turn = _nextTurn;
            _nextTurn = _nextTurn.next();
            _nextTransaction++;

            return new TransactionGuide<T, R, E>(timestamp, turn);
        }
    }

    private <R, E extends Exception> void notifySubscribers(TransactionGuide<T, R, E> guide) {
        guide.startTurn();
        try {
            _pausableClock.advanceTo(guide.executionTime());
            notifySubscribers(guide.timestamp());
        } finally {
            guide.endTurn();
        }
    }

    public void subscribe(TransactionSubscriber<T> subscriber, long initialTransaction) {
        synchronized (_pendingPublicationsMonitor) {
            while (_pendingPublications != 0) {
                Cool.wait(_pendingPublicationsMonitor);
            }

            _journal.update(subscriber, initialTransaction);

            synchronized (_nextTurnMonitor) {
                _nextTransaction = _journal.nextTransaction();
            }

            super.addSubscriber(subscriber);
        }
    }

    public void close() {
        _journal.close();
    }

}
