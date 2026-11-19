package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEditBook;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;

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
 * </ul>
 * A packet whose wrapper cannot be decoded is left alone (never cancelled on our own decode error:
 * the server's codec decides). Vanilla button/mode combinations are from the client's
 * {@code ClickType} handling; the slot and length limits are TUNING values set well above anything
 * a vanilla client sends. Prevention runs for every game mode; there is no exemption.
 */
public final class CrasherCheck extends Check implements PacketCheck {

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
                valid = click(click.getWindowClickType(), click.getButton(), click.getSlot());
            } else if (type == PacketType.Play.Client.EDIT_BOOK) {
                WrapperPlayClientEditBook book = new WrapperPlayClientEditBook(event);
                valid = book(book.getPages(), book.getTitle());
            } else if (type == PacketType.Play.Client.TAB_COMPLETE) {
                String text = new WrapperPlayClientTabComplete(event).getText();
                valid = tabComplete(text != null ? text.length() : 0);
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
