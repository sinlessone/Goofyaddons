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
        NAVIGATE_SELL_ORDER,
        CANCEL_ORDER,
        RELIST_NAVIGATION,
        SET_PRICE,
        CONFIRM
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
    private double newPrice = 0;

    private long lastUpdated;
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
    }

    @Override
    public void stop() {
        ChatUtils.clientMessage("AntiUnderBin: Stopped");
        enabled = false;
        state = State.IDLE;
        clock.stop();
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

                clock.start(checkInterval);
                if (clock.shouldFire()) {
                    state = State.CHECKING;
                }

                if (!booksToRelist.isEmpty()) {
                    state = State.NAVIGATE_SELL_ORDER;
                }
            }

            case CHECKING -> {
                checkOrders();
                state = State.IDLE;
            }

            case OPEN_BAZAAR -> {
                if (!isContainerOpen()) {
                    clock.start(randomDelay());
                }
                if (!isContainerOpen() && clock.shouldFire()) {
                    openBazaar("tomato");
                }

                if (containerCheck("Bazaar")) {
                    clock.start(randomDelay());
                }
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    if (!scannedOrders) {
                        state = State.SCAN_ORDERS;
                    } else if (!booksToRelist.isEmpty()) {
                        state = State.NAVIGATE_SELL_ORDER;
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

            case NAVIGATE_SELL_ORDER -> {
                if (booksToRelist.isEmpty()) {
                    state = State.IDLE;
                    return;
                }

                activeBook = booksToRelist.get(0);
                if (!isContainerOpen()) {
                    clock.start(randomDelay());
                }
                if (!isContainerOpen() && clock.shouldFire()) {
                    openBazaar("tomato");
                }

                if (containerCheck("Bazaar")) {
                    clock.start(randomDelay());
                }
                if (containerCheck("Bazaar") && clock.shouldFire()) {
                    List<Integer> sellSlots = inventoryScanner.getSellOrder();
                    boolean found = false;
                    for (int slot : sellSlots) {
                        String name = inventoryScanner.getName(slot);
                        if (name.contains("SELL") && name.contains(activeBook.name())) {
                            InventoryUtils.clickSlot(slot, false);
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        booksToRelist.remove(0);
                        ourSellOrders.remove(activeBook);
                    }
                }

                if (containerCheck("Order")) {
                    clock.start(randomDelay());
                }
                if (containerCheck("Order") && clock.shouldFire()) {
                    state = State.CANCEL_ORDER;
                }
            }

            case CANCEL_ORDER -> {
                List<Integer> cancelSlot = inventoryScanner.findContainer("Cancel Order");
                if (!cancelSlot.isEmpty()) {
                    InventoryUtils.clickSlot(cancelSlot.get(0), false);
                    state = State.RELIST_NAVIGATION;
                }
            }

            case RELIST_NAVIGATION -> {
                if (containerCheck(activeBook.name())) {
                    clock.start(randomDelay());
                }
                if (containerCheck(activeBook.name()) && clock.shouldFire()) {
                    InventoryUtils.clickSlot(16, false);
                }

                if (containerCheck("At what price are you selling")) {
                    clock.start(randomDelay());
                }
                if (containerCheck("At what price are you selling") && clock.shouldFire()) {
                    state = State.SET_PRICE;
                }
            }

            case SET_PRICE -> {
                InventoryUtils.clickSlot(12, false);
                state = State.CONFIRM;
            }

            case CONFIRM -> {
                if (containerCheck("Confirm")) {
                    clock.start(randomDelay());
                }
                if (containerCheck("Confirm") && clock.shouldFire()) {
                    InventoryUtils.clickSlot(13, false);
                    ourSellOrders.put(activeBook, newPrice);
                    booksToRelist.remove(0);
                    activeBook = null;
                    state = State.IDLE;
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
                    long lastUpdated = root.get("lastUpdated").getAsLong();
                    if (lastUpdated == this.lastUpdated) return;
                    this.lastUpdated = lastUpdated;

                    JsonObject products = root.getAsJsonObject("products");
                    checkForUndercut(products);
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
                    newPrice = bestPrice - 1;
                    debug("Undercut on " + book.name() + "! Our price: " + ourPrice + ", Best price: " + bestPrice + ", Relisting at: " + newPrice);
                }
            }
        }
    }

    private void scanSellOrders() {
        List<Integer> sellSlots = inventoryScanner.getSellOrder();
        for (int slot : sellSlots) {
            String name = inventoryScanner.getName(slot);
            double price = inventoryScanner.getUnitPrice(slot);

            for (Book book : GoofyConfig.INSTANCE.books) {
                if (name.contains(book.name())) {
                    ourSellOrders.put(book, price);
                    debug("Found sell order: " + book.name() + " at price " + price);
                    break;
                }
            }
        }
        debug("Scanned " + ourSellOrders.size() + " sell orders");
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
