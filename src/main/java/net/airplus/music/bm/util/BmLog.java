package net.airplus.music.bm.util;

import org.apache.logging.log4j.Logger;

import net.airplus.utils.client.ClientUtils;

/**
 * Bridge to the client logger for the migrated BetterMyau music player.
 */
public final class BmLog {
    private BmLog() {
    }

    public static final Logger LOGGER = ClientUtils.INSTANCE.getLOGGER();
}
