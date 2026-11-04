package com.sagaseer;

import java.awt.image.BufferedImage;
import net.runelite.client.util.ImageUtil;

/** The SagaSeer owl for the RuneLite sidebar button. */
final class SagaSeerSidebarIcon
{
    private SagaSeerSidebarIcon()
    {
    }

    static BufferedImage load()
    {
        // RuneLite's navigation UI scales the supplied image to its 16px tab.
        return ImageUtil.loadImageResource(SagaSeerSidebarIcon.class,
                "sagaseer-owl-transparent-128.png");
    }
}
