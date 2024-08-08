package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;


    Thread[] playersThreads;
    Thread dealerThread;
    protected volatile boolean allFreeze;
    public BlockingQueue<Integer> dealerQueue;
    public Integer win = 1;
    public Integer loose = -1;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        playersThreads = new Thread[players.length];
        dealerQueue = new ArrayBlockingQueue<>(players.length, true);
        terminate = false;
        allFreeze = false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = 0; i < players.length; i++) {
            playersThreads[i] = new Thread(players[i]);
            playersThreads[i].start();
        }
        dealerThread = Thread.currentThread();
        while (!shouldFinish()) {
            Collections.shuffle(deck);
            placeCardsOnTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            updateTimerDisplay(false);
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        if (!terminate) terminate();
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    private void betweenLoops() {
        for (Integer id : dealerQueue) {
            dealerQueue.remove(id);
        }
        for (Player player : players) {
            player.betweenLoopsOfPlayer();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (int id = players.length - 1; id >= 0; id--) {
            players[id].terminate();
        }
        terminate = true;
        dealerThread.interrupt();
    }


    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        while (!dealerQueue.isEmpty()) {
            synchronized (dealerQueue) {
                int playerId = dealerQueue.remove();
                int[] checkSet = table.playerChosenCards(playerId);
                if (checkSet.length == env.config.featureSize) {
                    synchronized (players[playerId].setCheck) {
                        if (env.util.testSet(checkSet)) {
                            allFreeze = true;
                            players[playerId].setCheck.add(win);
                            for (int card : checkSet) {
                                int slot = table.cardToSlot[card];
                                Set<Integer> currSet = table.playersPerSlot.get(slot);
                                for (int id : currSet) {
                                    players[id].myTokens.remove(slot);
                                    if (id != playerId && dealerQueue.contains(id)) {
                                        dealerQueue.remove(id);
                                        synchronized (players[id].setCheck) {
                                            players[id].setCheck.notifyAll();
                                        }
                                    }
                                }
                                table.removeCard(slot);
                            }
                            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
                            players[playerId].setCheck.notifyAll();
                        } else {
                            players[playerId].setCheck.add(loose);
                            players[playerId].setCheck.notifyAll();
                        }
                    }
                    updateTimerDisplay(false);
                } else {
                    synchronized (players[playerId].setCheck) {
                        players[playerId].setCheck.notifyAll();
                    }
                }
                dealerQueue.notifyAll();
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        allFreeze = true;
        List<Integer> places = new ArrayList<>();
        for (int j = 0; j < env.config.tableSize; j++)
            places.add(j);
        Collections.shuffle(places);
        if (table.countCards() != env.config.tableSize && !deck.isEmpty()) {
            for (int i = 0; i < env.config.tableSize; i++) {
                if (table.slotToCard[places.get(i)] == null) {
                    if (!deck.isEmpty())
                        table.placeCard(deck.remove(0), places.get(i));
                }
            }
        }
        allFreeze = false;

    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if (dealerQueue.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        long time = reshuffleTime - System.currentTimeMillis();
        reset = time < env.config.turnTimeoutWarningMillis & time > 0;
        env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), reset);
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        allFreeze = true;
        List<Integer> places = new ArrayList<>();
        for (int j = 0; j < env.config.tableSize; j++)
            places.add(j);
        Collections.shuffle(places);
        for (int i = 0; i < env.config.tableSize; i++) {
            if (table.slotToCard[places.get(i)] != null) {
                deck.add(table.slotToCard[places.get(i)]);
                table.removeCard(places.get(i));
            }
        }
        betweenLoops();

    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max = -1;
        int winnersAmount = 1;
        for (Player player : players) {
            if (player.score() > max) {
                max = player.score();
                winnersAmount = 1;
            } else if (player.score() == max) {
                winnersAmount++;
            }
        }
        int[] winners = new int[winnersAmount];
        int index = 0;
        for (Player player : players) {
            if (player.score() == max) {
                winners[index] = player.id;
                index++;
            }
        }
        env.ui.announceWinner(winners);
    }
}


