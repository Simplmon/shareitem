package dev.shareitem;

import org.bukkit.Material;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public class PendingRequest {
    private final UUID id;
    private final UUID source;
    private final UUID target;
    private final String targetName;
    private final String sourceName;
    private final Material material;
    private final int amount;
    private final String sharedItemDisplay;
    private final long createdAt;
    private BukkitTask cancelWindowTask;
    private BukkitTask expireTask;
    private boolean cancelled = false;
    private boolean handled = false;
    private boolean notified = false;

    public PendingRequest(UUID id, UUID source, String sourceName, UUID target, String targetName,
                          Material material, int amount, String sharedItemDisplay) {
        this.id = id;
        this.source = source;
        this.sourceName = sourceName;
        this.target = target;
        this.targetName = targetName;
        this.material = material;
        this.amount = amount;
        this.sharedItemDisplay = sharedItemDisplay;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getId() { return id; }
    public UUID getSource() { return source; }
    public UUID getTarget() { return target; }
    public String getTargetName() { return targetName; }
    public String getSourceName() { return sourceName; }
    public Material getMaterial() { return material; }
    public int getAmount() { return amount; }
    public String getSharedItemDisplay() { return sharedItemDisplay; }
    public long getCreatedAt() { return createdAt; }
    public BukkitTask getCancelWindowTask() { return cancelWindowTask; }
    public void setCancelWindowTask(BukkitTask t) { this.cancelWindowTask = t; }
    public BukkitTask getExpireTask() { return expireTask; }
    public void setExpireTask(BukkitTask t) { this.expireTask = t; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean c) { this.cancelled = c; }
    public boolean isHandled() { return handled; }
    public void setHandled(boolean h) { this.handled = h; }
    public boolean isNotified() { return notified; }
    public void setNotified(boolean n) { this.notified = n; }
}
