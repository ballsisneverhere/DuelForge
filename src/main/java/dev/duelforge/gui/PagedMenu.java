package dev.duelforge.gui;

import dev.duelforge.DuelForge;
import dev.duelforge.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** A 6-row menu listing entries over multiple pages with back / previous / next controls. */
public abstract class PagedMenu<T> extends Menu {
    protected static final int[] SLOTS = buildSlots();
    protected static final int BACK = 45, PREV = 48, INFO = 49, NEXT = 50, EXTRA = 53;

    protected int page = 0;
    protected List<T> entries = new ArrayList<>();
    protected final Runnable back;

    protected PagedMenu(DuelForge plugin, Player viewer, String title, Runnable back) {
        super(plugin, viewer, 6, title);
        this.back = back;
    }

    private static int[] buildSlots() {
        int[] s = new int[28];
        int i = 0;
        for (int row = 1; row <= 4; row++) for (int col = 1; col <= 7; col++) s[i++] = row * 9 + col;
        return s;
    }

    protected abstract List<T> load();

    protected abstract ItemStack entryIcon(T entry);

    protected abstract void select(T entry, InventoryClickEvent e);

    /** Optional button in the bottom-right corner. */
    protected ItemStack extra() {
        return null;
    }

    protected void extraClick(InventoryClickEvent e) {}

    @Override
    public void build() {
        inv.clear();
        border(Material.GRAY_STAINED_GLASS_PANE);
        entries = load();
        int pages = Math.max(1, (entries.size() + SLOTS.length - 1) / SLOTS.length);
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * SLOTS.length;
        for (int i = 0; i < SLOTS.length && start + i < entries.size(); i++) set(SLOTS[i], entryIcon(entries.get(start + i)));
        if (entries.isEmpty()) set(22, Items.make(Material.BARRIER, t("gui.nothing")));
        if (back != null) set(BACK, Items.make(Material.ARROW, t("gui.back")));
        if (page > 0) set(PREV, Items.make(Material.ARROW, t("gui.previous")));
        if (pages > 1) set(INFO, Items.make(Material.PAPER, t("gui.page", "page", page + 1, "pages", pages)));
        if (page < pages - 1) set(NEXT, Items.make(Material.ARROW, t("gui.next")));
        ItemStack ex = extra();
        if (ex != null) set(EXTRA, ex);
    }

    @Override
    public void onClick(InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (slot == BACK && back != null) {
            later(back);
        } else if (slot == PREV && page > 0) {
            page--;
            refresh();
        } else if (slot == NEXT) {
            page++;
            refresh();
        } else if (slot == EXTRA) {
            extraClick(e);
        } else {
            for (int i = 0; i < SLOTS.length; i++) {
                if (SLOTS[i] != slot) continue;
                int idx = page * SLOTS.length + i;
                if (idx < entries.size()) select(entries.get(idx), e);
                return;
            }
        }
    }
}
