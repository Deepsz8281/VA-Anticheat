package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEditBook;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientNameItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSelectBundleItem;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSettings;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

import java.util.List;

/**
 * Crash protection ("crasher"): drops inbound packets that are only useful to lag or crash the
 * server, and reports the sender. Unlike detection checks this one <em>cancels</em> the packet
 * (prevention), so the server never decodes or handles it; the violation then feeds the
 * {@code packet} punishment profile (kick).
 *
 * <p>Signals, cheapest first (only the last three construct a wrapper, and only after the size test):
 * <ul>
 *   <li><b>rate</b> — more than {@code max-packets-per-second} play packets within one second.
 *       Packets above the limit are dropped; every exceeded window raises the buffer once. A normal
 *       client sends well under 200 per second; a lag burst replays at most the packets of the
 *       frozen seconds.</li>
 *   <li><b>size</b> — a packet body above {@code max-packet-bytes}. Vanilla limits custom payloads
 *       to 32767 bytes; book edits and creative item packets are exempt (the server applies its own
 *       limits to them, and legit written books can be large).</li>
 *   <li><b>window click</b> — a click mode/button combination the vanilla client never produces,
 *       or a slot outside {@code -999..max-window-slot}.</li>
 *   <li><b>book</b> — more than {@code max-book-pages} pages, a page longer than
 *       {@code max-book-page-length} characters or a title longer than {@code max-book-title-length}.</li>
 *   <li><b>tab complete</b> — command suggestion requests longer than {@code max-tab-complete-length}.</li>
 *   <li><b>position</b> (Grim CrashA) — a position beyond the hard world limit (|x|, |z| above
 *       29 999 999) or a non-finite coordinate.</li>
 *   <li><b>creative</b> (CrashB) — creative inventory packets outside creative mode (after the join
 *       was seen, not during a game mode change).</li>
 *   <li><b>lectern</b> (CrashD) — slot clicks in an open lectern, which has no clickable slots.</li>
 *   <li><b>view distance</b> (CrashE) — a view distance below 2 is raised to 2 and reported.</li>
 *   <li><b>sequence</b> (CrashG) — a negative block or item sequence number.</li>
 *   <li><b>bundle</b> (CrashI) — a bundle selection index below -1.</li>
 *   <li><b>item name</b> (ExploitA) — an anvil name longer than 50 characters.</li>
 * </ul>
 * A packet whose wrapper cannot be decoded is left alone (never cancelled on our own decode error:
 * the server's codec decides). Vanilla button/mode combinations are from the client's
 * {@code ClickType} handling; the slot and length limits are TUNING values set well above anything
 * a vanilla client sends. Prevention runs for every game mode; there is no exemption.
 */
public final class CrasherCheck extends Check implements PacketCheck, OutboundPacketCheck {

    public static final int DEFAULT_MAX_PACKETS_PER_SECOND = 600;
    public static final int DEFAULT_MAX_PACKET_BYTES = 65_536;
    public static final int DEFAULT_MAX_WINDOW_SLOT = 255;
    public static final int DEFAULT_MAX_BOOK_PAGES = 100;
    public static final int DEFAULT_MAX_BOOK_PAGE_LENGTH = 1024;
    public static final int DEFAULT_MAX_BOOK_TITLE_LENGTH = 32;
    public static final int DEFAULT_MAX_TAB_COMPLETE_LENGTH = 2048;
    /** Slot id of a click outside the window (vanilla). */
    public static final int OUTSIDE_SLOT = -999;
    private static final long WINDOW_NANOS = 1_000_000_000L;
    /** Vanilla's hard coordinate limit (Grim CrashA). */
    public static final double WORLD_LIMIT = 2.9999999E7;
    public static final int MIN_VIEW_DISTANCE = 2;
    /** Anvil name limit of 1.17+ clients. */
    public static final int MAX_ITEM_NAME = 50;
    private static final long CREATIVE_EXEMPT = ExemptionCause.mask(ExemptionCause.GAMEMODE_CHANGE,
            ExemptionCause.JOIN, ExemptionCause.RESPAWN);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("crasher", CheckCategory.PACKET)
            .displayName("Crasher")
            .description("Drops packets that can only lag or crash the server")
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(0.0)
                    .withVlDecayPerMinute(2.0))
            .build();

    private int maxPacketsPerSecond = DEFAULT_MAX_PACKETS_PER_SECOND;
    private int maxPacketBytes = DEFAULT_MAX_PACKET_BYTES;
    private int maxWindowSlot = DEFAULT_MAX_WINDOW_SLOT;
    private int maxBookPages = DEFAULT_MAX_BOOK_PAGES;
    private int maxBookPageLength = DEFAULT_MAX_BOOK_PAGE_LENGTH;
    private int maxBookTitleLength = DEFAULT_MAX_BOOK_TITLE_LENGTH;
    private int maxTabCompleteLength = DEFAULT_MAX_TAB_COMPLETE_LENGTH;

    private long windowStart;
    private int windowCount;
    private boolean windowFlagged;
    private long dropped;

    private boolean checkPosition = true;
    private boolean checkCreative = true;
    private boolean checkLectern = true;
    private boolean checkViewDistance = true;
    private boolean checkSequence = true;
    private boolean checkBundle = true;
    private boolean checkItemName = true;

    private boolean joinSeen;
    private int lecternWindow = -1;
    /** Menu registry id of the lectern for the running server, -1 when unknown. */
    private final int lecternMenuId = lecternMenuId();

    public CrasherCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxPacketsPerSecond = clamp(options.getInt("max-packets-per-second", DEFAULT_MAX_PACKETS_PER_SECOND),
                100, 100_000, DEFAULT_MAX_PACKETS_PER_SECOND);
        maxPacketBytes = clamp(options.getInt("max-packet-bytes", DEFAULT_MAX_PACKET_BYTES),
                32_768, 8_388_608, DEFAULT_MAX_PACKET_BYTES);
        maxWindowSlot = clamp(options.getInt("max-window-slot", DEFAULT_MAX_WINDOW_SLOT),
                90, 32_767, DEFAULT_MAX_WINDOW_SLOT);
        maxBookPages = clamp(options.getInt("max-book-pages", DEFAULT_MAX_BOOK_PAGES),
                1, 10_000, DEFAULT_MAX_BOOK_PAGES);
        maxBookPageLength = clamp(options.getInt("max-book-page-length", DEFAULT_MAX_BOOK_PAGE_LENGTH),
                256, 32_767, DEFAULT_MAX_BOOK_PAGE_LENGTH);
        maxBookTitleLength = clamp(options.getInt("max-book-title-length", DEFAULT_MAX_BOOK_TITLE_LENGTH),
                16, 1024, DEFAULT_MAX_BOOK_TITLE_LENGTH);
        maxTabCompleteLength = clamp(options.getInt("max-tab-complete-length", DEFAULT_MAX_TAB_COMPLETE_LENGTH),
                256, 32_767, DEFAULT_MAX_TAB_COMPLETE_LENGTH);
        checkPosition = options.getBoolean("check-position", true);
        checkCreative = options.getBoolean("check-creative", true);
        checkLectern = options.getBoolean("check-lectern", true);
        checkViewDistance = options.getBoolean("check-view-distance", true);
        checkSequence = options.getBoolean("check-sequence", true);
        checkBundle = options.getBoolean("check-bundle", true);
        checkItemName = options.getBoolean("check-item-name", true);
    }

    /** The crafter menu (1.20.3) shifted the lectern from 16 to 17; no menu was added since. */
    private static int lecternMenuId() {
        try {
            ServerVersion version = PacketEvents.getAPI() != null
                    ? PacketEvents.getAPI().getServerManager().getVersion() : null;
            if (version == null) {
                return -1;
            }
            return version.isNewerThanOrEquals(ServerVersion.V_1_20_3) ? 17 : 16;
        } catch (RuntimeException unknown) {
            return -1;
        }
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        windowStart = 0L;
        windowCount = 0;
        windowFlagged = false;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Client)) {
            return;
        }
        if (!rate(player.context().time().nanoTime())) {
            cancel(event);
            return;
        }
        if (type != PacketType.Play.Client.EDIT_BOOK && type != PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            int bytes = ByteBufHelper.readableBytes(event.getByteBuf());
            if (!size(type, bytes)) {
                cancel(event);
                return;
            }
        }
        boolean valid;
        try {
            if (type == PacketType.Play.Client.CLICK_WINDOW) {
                WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
                valid = click(click.getWindowClickType(), click.getButton(), click.getSlot())
                        && lectern(click.getWindowId());
            } else if (type == PacketType.Play.Client.EDIT_BOOK) {
                WrapperPlayClientEditBook book = new WrapperPlayClientEditBook(event);
                valid = book(book.getPages(), book.getTitle());
            } else if (type == PacketType.Play.Client.TAB_COMPLETE) {
                String text = new WrapperPlayClientTabComplete(event).getText();
                valid = tabComplete(text != null ? text.length() : 0);
            } else if (type == PacketType.Play.Client.PLAYER_POSITION
                    || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
                WrapperPlayClientPlayerFlying move = new WrapperPlayClientPlayerFlying(event);
                valid = position(move.getLocation().getX(), move.getLocation().getY(), move.getLocation().getZ());
            } else if (type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
                valid = creative();
            } else if (type == PacketType.Play.Client.CLIENT_SETTINGS) {
                WrapperPlayClientSettings settings = new WrapperPlayClientSettings(event);
                if (!viewDistance(settings.getViewDistance())) {
                    settings.setViewDistance(MIN_VIEW_DISTANCE);
                    event.markForReEncode(true);
                }
                return;
            } else if (type == PacketType.Play.Client.USE_ITEM) {
                valid = sequence(new WrapperPlayClientUseItem(event).getSequence(), "use");
            } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
                valid = sequence(new WrapperPlayClientPlayerBlockPlacement(event).getSequence(), "place");
            } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
                valid = sequence(new WrapperPlayClientPlayerDigging(event).getSequence(), "dig");
            } else if (type == PacketType.Play.Client.SELECT_BUNDLE_ITEM) {
                valid = bundle(bundleIndex(event));
            } else if (type == PacketType.Play.Client.NAME_ITEM) {
                String name = new WrapperPlayClientNameItem(event).getItemName();
                valid = itemName(name != null ? name.length() : 0);
            } else {
                return;
            }
        } catch (RuntimeException decodeError) {
            // Our decode failed; the server's codec decides about the packet. Never drop on our own error.
            return;
        }
        if (!valid) {
            cancel(event);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.OPEN_WINDOW) {
            WrapperPlayServerOpenWindow open = new WrapperPlayServerOpenWindow(event);
            onOpenWindow(open.getContainerId(), lecternMenuId >= 0 && open.getType() == lecternMenuId);
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            joinSeen = true;
        }
    }

    /** A window the server opens; only lecterns are remembered. */
    void onOpenWindow(int windowId, boolean lectern) {
        lecternWindow = lectern ? windowId : -1;
    }

    void onJoin() {
        joinSeen = true;
    }

    /** The bundle index; PacketEvents rejects indexes below -1 while decoding. */
    private static int bundleIndex(PacketReceiveEvent event) {
        try {
            return new WrapperPlayClientSelectBundleItem(event).getSelectedItemIndex();
        } catch (IllegalArgumentException invalid) {
            String message = invalid.getMessage();
            String prefix = "Invalid selectedItemIndex: ";
            if (message != null && message.startsWith(prefix)) {
                try {
                    return Integer.parseInt(message.substring(prefix.length()).trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
            throw invalid;
        }
    }

    /** @return {@code false} for a position beyond the world limit */
    boolean position(double x, double y, double z) {
        if (!checkPosition) {
            return true;
        }
        boolean finite = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        if (finite && Math.abs(x) <= WORLD_LIMIT && Math.abs(z) <= WORLD_LIMIT && Math.abs(y) <= Integer.MAX_VALUE) {
            return true;
        }
        flag("position x=" + x + " y=" + y + " z=" + z);
        return false;
    }

    /** @return {@code false} for a creative inventory packet outside creative mode */
    boolean creative() {
        if (!checkCreative || !joinSeen || player.abilities().gameMode() == GameMode.CREATIVE
                || player.exemptions().anyActive(CREATIVE_EXEMPT)) {
            return true;
        }
        flag("creative inventory action in " + player.abilities().gameMode());
        return false;
    }

    /** @return {@code false} for a slot click inside an open lectern */
    boolean lectern(int windowId) {
        if (!checkLectern || windowId <= 0 || windowId != lecternWindow) {
            return true;
        }
        flag("lectern click window=" + windowId);
        return false;
    }

    /** @return {@code false} for a view distance below the minimum (the packet is then corrected) */
    boolean viewDistance(int distance) {
        if (!checkViewDistance || distance >= MIN_VIEW_DISTANCE) {
            return true;
        }
        flag("view-distance " + distance);
        return false;
    }

    /** @return {@code false} for a negative sequence number */
    boolean sequence(int sequence, String action) {
        if (!checkSequence || sequence >= 0) {
            return true;
        }
        flag("sequence " + action + " " + sequence);
        return false;
    }

    /** @return {@code false} for a bundle index below -1 */
    boolean bundle(int index) {
        if (!checkBundle || index >= -1) {
            return true;
        }
        flag("bundle index=" + index);
        return false;
    }

    /** @return {@code false} for an anvil name above the vanilla limit */
    boolean itemName(int length) {
        if (!checkItemName || length <= MAX_ITEM_NAME) {
            return true;
        }
        flag("item-name length=" + length);
        return false;
    }

    private void cancel(PacketReceiveEvent event) {
        event.setCancelled(true);
        dropped++;
    }

    /**
     * Counts one packet in the one-second window.
     *
     * @return {@code false} when the packet exceeds the rate and must be dropped
     */
    boolean rate(long nowNanos) {
        if (windowStart == 0L || nowNanos - windowStart >= WINDOW_NANOS) {
            windowStart = nowNanos;
            windowCount = 0;
            windowFlagged = false;
        }
        windowCount++;
        if (windowCount <= maxPacketsPerSecond) {
            return true;
        }
        if (!windowFlagged) {
            windowFlagged = true;
            flag("rate>" + maxPacketsPerSecond + "/s");
        }
        return false;
    }

    /** @return {@code false} when the packet body is too large */
    boolean size(PacketTypeCommon type, int bytes) {
        if (bytes <= maxPacketBytes) {
            return true;
        }
        flag("size type=" + type.getName() + " bytes=" + bytes + " limit=" + maxPacketBytes);
        return false;
    }

    /** @return {@code false} for a click the vanilla client cannot produce */
    boolean click(WrapperPlayClientClickWindow.WindowClickType mode, int button, int slot) {
        boolean slotValid = slot == OUTSIDE_SLOT || (slot >= -1 && slot <= maxWindowSlot);
        boolean buttonValid = mode != null && switch (mode) {
            case PICKUP, QUICK_MOVE, THROW -> button == 0 || button == 1;
            case SWAP -> (button >= 0 && button <= 8) || button == 40;
            case CLONE -> button == 2;
            case QUICK_CRAFT -> button >= 0 && button <= 10 && button != 3 && button != 7;
            case PICKUP_ALL -> button == 0;
            default -> false;
        };
        if (slotValid && buttonValid) {
            return true;
        }
        flag("click mode=" + mode + " button=" + button + " slot=" + slot);
        return false;
    }

    /** @return {@code false} for a book above the page/length limits */
    boolean book(List<String> pages, String title) {
        int pageCount = pages != null ? pages.size() : 0;
        int longest = 0;
        if (pages != null) {
            for (String page : pages) {
                if (page != null && page.length() > longest) {
                    longest = page.length();
                }
            }
        }
        int titleLength = title != null ? title.length() : 0;
        if (pageCount <= maxBookPages && longest <= maxBookPageLength && titleLength <= maxBookTitleLength) {
            return true;
        }
        flag("book pages=" + pageCount + " longest=" + longest + " title=" + titleLength);
        return false;
    }

    /** @return {@code false} for an over-long suggestion request */
    boolean tabComplete(int length) {
        if (length <= maxTabCompleteLength) {
            return true;
        }
        flag("tab-complete length=" + length + " limit=" + maxTabCompleteLength);
        return false;
    }

    private void flag(String details) {
        if (debugging()) {
            debug(details);
        }
        if (raiseBuffer(1.0)) {
            violation(details);
            resetBuffer();
        }
    }

    /** Packets dropped for this player since join (diagnostics, tests). */
    public long dropped() {
        return dropped;
    }
}
