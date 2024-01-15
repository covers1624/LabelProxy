package net.covers1624.lp.logging;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

/**
 * Created by covers1624 on 15/1/24.
 */
public class Markers {

    public static final Marker DISCORD = MarkerManager.getMarker("DISCORD");
    public static final Marker DISCORD_ONLY = MarkerManager.getMarker("DISCORD_ONLY").addParents(DISCORD);
}
