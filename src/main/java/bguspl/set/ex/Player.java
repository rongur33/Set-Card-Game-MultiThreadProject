package bguspl.set.ex;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */

    private int score;
    //queue of the player actions made by the keyboard
    private BlockingQueue<Integer> actions;
    //dealer of the game 
    private Dealer dealer;
    public long penaltyTime;
    public BlockingQueue<Integer> myTokens;

    public BlockingQueue<Integer> setCheck;
    private volatile boolean froze;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        score = 0;
        penaltyTime = System.currentTimeMillis();
        this.actions = new LinkedBlockingQueue<>(env.config.featureSize);
        myTokens = new LinkedBlockingQueue<>(env.config.featureSize);
        this.setCheck = new LinkedBlockingQueue<>(1);
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human) createArtificialIntelligence();
        while (!terminate) {
            if (!actions.isEmpty()) {
                int slot = actions.remove();
                if (table.slotToCard[slot] != null) {
                    if (table.removeToken(id, slot)) {
                        myTokens.remove(slot);
                    } else if (myTokens.size() < env.config.featureSize) {
                        table.placeToken(id, slot);
                        myTokens.add(slot);
                        if (myTokens.size() == env.config.featureSize) {
                            synchronized (dealer.dealerQueue) {
                                if (myTokens.size() == env.config.featureSize) {
                                    dealer.dealerQueue.add(id);
                                }
                                dealer.dealerQueue.notifyAll();
                            }
                            synchronized (setCheck) {
                                while (setCheck.isEmpty() && myTokens.size() == env.config.featureSize) {
                                    try {
                                        setCheck.wait();
                                    } catch (InterruptedException ignored) {}
                                }
                            }
                            if (!setCheck.isEmpty()) {
                                if (setCheck.remove() == dealer.win) {
                                    point();
                                } else {
                                    penalty();
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!human) try {
            aiThread.join();
        } catch (InterruptedException ignored) {
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                Random r = new Random();
                int i = r.nextInt(env.config.tableSize);
                keyPressed(i);
                try {
                    Thread.sleep(5);
                }catch (InterruptedException e){}
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        if (!human) aiThread.interrupt();
        playerThread.interrupt();
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (!froze && !dealer.allFreeze) {
            try {
                actions.put(slot);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        froze = true;
        score++;
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, score);
        penaltyTime = System.currentTimeMillis() + env.config.pointFreezeMillis;
        try {
            for (long i = penaltyTime - System.currentTimeMillis(); i > 0; i -= 1000) {
                env.ui.setFreeze(id, i);
                Thread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException e) {
        }
        froze = false;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        froze = true;
        penaltyTime = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
        try {
            for (long i = penaltyTime - System.currentTimeMillis(); i > 0; i -= 1000) {
                env.ui.setFreeze(id, i);
                Thread.sleep(1000);
            }
            env.ui.setFreeze(id, 0);
        } catch (InterruptedException e) {
        }
        froze = false;
    }


    public int score() {
        return score;
    }

    public void betweenLoopsOfPlayer() {
        for (Integer place : myTokens) {
            myTokens.remove(place);
        }
        for (Integer spot : actions) {
            actions.remove(spot);
        }
        synchronized (setCheck) {
            for (int decsion : setCheck) {
                setCheck.remove(decsion);
            }
            setCheck.notifyAll();
        }
    }


}
