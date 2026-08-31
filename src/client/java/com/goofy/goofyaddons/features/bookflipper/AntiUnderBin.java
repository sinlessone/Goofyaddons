package com.goofy.goofyaddons.features.bookflipper;

import com.goofy.goofyaddons.config.GoofyConfig;
import com.goofy.goofyaddons.features.Feature;
import com.goofy.goofyaddons.features.bookflipper.helper.Book;
import com.goofy.goofyaddons.utils.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

public class AntiUnderBin implements Feature {
    enum State {
        IDLE,
        CHECKING,
        OPEN_BAZAAR,
        SCAN_ORDERS,
        REPLACE_SELL
    }

    private boolean enabled = false;
    private State state = State.IDLE;
    private State lastState = null;
    private Clock clock = new Clock();
    private Minecraft minecraft = Minecraft.getInstance();
    private InventoryScanner inventoryScanner = new InventoryScanner();
    private HttpClient client = HttpClient.newHttpClient();
    private SplittableRandom random = new SplittableRandom();

    private Map<Book, Double> ourSellOrders = new LinkedHashMap<>();
    private List<Book> booksToRelist = new ArrayList<>();
    private Book activeBook = null;

    private int relistWaitCounter = 0;
    private long lastUpdated = 0;
    private long checkInterval = 15000;
    private boolean scannedOrders = false;

    @Override
    public String name() {
        return "AntiUnderBin";
    }

    @Override
    public void start() {
        ChatUtils.clientMessage("AntiUnderBin: Started - Monitoring sell orders");
        if (minecraft.screen != null) {
            minecraft.player.closeContainer();
        }
        enabled = true;
        state = State.IDLE;
        ourSellOrders.clear();
        booksToRelist.clear();
        scannedOrders = false;
        activeBook = null;
        relistWaitCounter = 0;
        lastUpdated = 0;
    }

    @Override
    public void stop() {
        ChatUtils.clientMessage("AntiUnderBin: Stopped");
        enabled = false;
        state = State.IDLE;
        clock.stop();
    }

    @Override
    public void pause() {
        enabled = false;
    }

    @Override
    public void resume() {
        enabled = true;
    }

    @Override
    public void onTick() {
        if (!enabled) return;

        lastStateCheck();

        switch (state) {
            case IDLE -> {
                if (!scannedOrders) {
                    state = State.OPEN_BAZAAR;
                    return;
                }

                if (!booksToRelist.isEmpty()) {
                    state = State.REPLACE_SELL;
                    return;
                }

                clock.start(checkInterval);
                if (clock.shouldFire()) {
                    state = State.CHECKING;
                }
            }

            case CHECKING -> {
                checkOrders();
                state = State.IDLE;
            }

            case OPEN_BAZAAR -> {
                if (!isContainerOpen()) clock.start(randomDelay());
                if (!isContainerOpen() && clock.shouldFire()) {
                    openBazaar("tomato");
                }

                if (containerCheck("tomato")) clock.start(randomDelay());
                if (containerCheck("tomato") && clock.shouldFire()) {
                    InventoryUtils.clickSlot(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomDelay());
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    if (!scannedOrders) {
                        state = State.SCAN_ORDERS;
                    } else {
                        state = State.IDLE;
                        minecraft.player.closeContainer();
                    }
                }
            }

            case SCAN_ORDERS -> {
                scanSellOrders();
                scannedOrders = true;
                state = State.IDLE;
                minecraft.player.closeContainer();
            }

            case REPLACE_SELL -> {
                if (!isContainerOpen()) clock.start(randomDelay());
                if (!isContainerOpen() && clock.shouldFire()) {
                    debug("no container, opening bazaar for tomato");
                    openBazaar("tomato");
                }

                if (containerCheck("tomato")) clock.start(randomDelay());
                if (containerCheck("tomato") && clock.shouldFire()) {
                    debug("tomato bazaar open, clicking slot 50");
                    InventoryUtils.clickSlot(50, false);
                }

                if (containerCheck("Bazaar")) clock.start(randomDelay());
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    if (booksToRelist.isEmpty()) {
                        scannedOrders = false; // Force rescan to verify actual prices
                        state = State.IDLE;
                        minecraft.player.closeContainer();
                        return;
                    }

                    activeBook = booksToRelist.get(0);
                    String activeBookTarget = activeBook.getRomanLevel(activeBook.sellLevel());
                    List<Integer> slots = inventoryScanner.getSellOrder();

                    boolean foundOrder = false;
                    for (int slot : slots) {
                        String name = inventoryScanner.getName(slot);
                        if (name.contains("SELL") && name.contains(activeBookTarget)) {
                            debug("Found active sell order for " + activeBookTarget + ", clicking slot " + slot);
                            InventoryUtils.clickSlot(slot, false);
                            foundOrder = true;
                            relistWaitCounter = 0;
                            break;
                        }
                    }

                    if (!foundOrder) {
                        List<Integer> invSlots = inventoryScanner.findLoreInv(activeBookTarget);

                        // Found item directly in inventory (post-cancellation)
                        if (!invSlots.isEmpty()) {
                            relistWaitCounter = 0;
                            debug("Found " + activeBookTarget + " in inventory, clicking slot " + invSlots.get(0));
                            InventoryUtils.clickSlot(invSlots.get(0), false);
                            return;
                        }

                        // Order cancelled but hasn't entered inventory just yet
                        relistWaitCounter++;
                        debug("Waiting for " + activeBookTarget + " to appear in inventory... (" + relistWaitCounter + "/20)");
                        if (relistWaitCounter > 20) {
                            debug("Timed out waiting for item in inventory. Skipping this relist.");
                            booksToRelist.remove(0);
                            relistWaitCounter = 0;
                        }
                        return;
                    }
                }

                if (containerCheck("Order")) clock.start(randomDelay());
                if (containerCheck("Order") && clock.shouldFire()) {
                    List<Integer> slot = inventoryScanner.findContainer("Cancel Order");
                    if (slot.isEmpty()) return;
                    debug("Order screen open, clicking Cancel Order slot " + slot.get(0));
                    InventoryUtils.clickSlot(slot.get(0), false);
                }

                if (activeBook != null && containerCheck(activeBook.name())) clock.start(randomDelay());
                if (activeBook != null && containerCheck(activeBook.name()) && clock.shouldFire()) {
                    debug("book screen open (" + activeBook.name() + "), clicking slot 16");
                    InventoryUtils.clickSlot(16, false);
                }

                if (containerCheck("At what price are you selling")) clock.start(randomDelay());
                if (containerCheck("At what price are you selling") && clock.shouldFire()) {
                    debug("price prompt, clicking slot 12 (Top offer - 0.1)");
                    InventoryUtils.clickSlot(12, false);
                }

                if (containerCheck("Confirm")) clock.start(randomDelay());
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    debug("confirm prompt, clicking slot 13");
                    InventoryUtils.clickSlot(13, false);

                    if (!booksToRelist.isEmpty()) {
                        booksToRelist.remove(0);
                    }
                    activeBook = null;
                    relistWaitCounter = 0;

                    if (booksToRelist.isEmpty()) {
                        debug("Finished relisting, triggering live rescan of new prices.");
                        state = State.IDLE;
                        scannedOrders = false; // Forces it to physically read the exact server price
                        minecraft.player.closeContainer();
                    }
                }
            }
        }
    }

    private void checkOrders() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.hypixel.net/v2/skyblock/bazaar"))
                .GET()
                .build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(body -> JsonParser.parseString(body).getAsJsonObject())
                .thenAccept(root -> {
                    // Force the processing back onto the main thread to avoid Memory Visibility drift
                    minecraft.execute(() -> {
                        if (!enabled) return;

                        long currentUpdated = root.get("lastUpdated").getAsLong();
                        if (currentUpdated == this.lastUpdated) return;
                        this.lastUpdated = currentUpdated;

                        JsonObject products = root.getAsJsonObject("products");
                        checkForUndercut(products);
                    });
                }).exceptionally(e -> {
                    debug("API Fetch failed: " + e.getMessage());
                    return null;
                });
    }

    private void checkForUndercut(JsonObject products) {
        for (Map.Entry<Book, Double> entry : ourSellOrders.entrySet()) {
            Book book = entry.getKey();
            double ourPrice = entry.getValue();

            JsonObject product = products.getAsJsonObject(book.getLevel(book.sellLevel()));
            if (product == null) continue;

            if (product.getAsJsonArray("sell_summary").size() == 0) continue;

            JsonObject sellSummary = product.getAsJsonArray("sell_summary").get(0).getAsJsonObject();
            double bestPrice = sellSummary.get("pricePerUnit").getAsDouble();

            if (bestPrice < ourPrice) {
                if (!booksToRelist.contains(book)) {
                    booksToRelist.add(book);
                    debug("Undercut detected on " + book.name() + "! Our price: " + ourPrice + ", Best price: " + bestPrice);
                }
            }
        }
    }

    private void scanSellOrders() {
        ourSellOrders.clear(); // Clear old prices to ensure we only track active orders
        List<Integer> sellSlots = inventoryScanner.getSellOrder();
        for (int slot : sellSlots) {
            String name = inventoryScanner.getName(slot);
            double price = inventoryScanner.getSellOrderPrice(slot);

            for (Book book : GoofyConfig.INSTANCE.books) {
                if (name.contains(book.name())) {
                    ourSellOrders.put(book, price);
                    debug("Scanned active order for " + book.name() + " at true price: " + price);
                    break;
                }
            }
        }
    }

    private void debug(String msg) {
        ChatUtils.debugMessage("[" + state + "] " + msg);
    }

    private boolean isContainerOpen() {
        return minecraft.screen != null;
    }

    private boolean containerCheck(String name) {
        if (minecraft.screen == null) return false;
        String title = minecraft.screen.getTitle().getString();
        return title.toLowerCase().contains(name.toLowerCase());
    }

    private void openBazaar(String name) {
        if (containerCheck("bazaar")) return;
        minecraft.player.connection.sendCommand("bz " + name);
    }

    private int randomDelay() {
        return random.nextInt(100, 500);
    }

    private void lastStateCheck() {
        if (state != lastState) {
            debug("state changed: " + lastState + " -> " + state);
            clock.stop();
            lastState = state;
        }
    }
}