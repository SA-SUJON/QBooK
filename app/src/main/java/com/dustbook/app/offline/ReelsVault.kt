package com.dustbook.app.offline

/**
 * The reels' own offline system.
 *
 * A reel is a video: an entry whose capture brought no playable video URL
 * along is filtered out before it ever reaches the store, because a reel
 * that cannot play is not a reel. Its count is a reel counted only when
 * its video file is fully on disk in `offline_vaults/reels/media/` -
 * files there are renamed into place after their last byte, so a file
 * that exists is a file that plays.
 *
 * The user's chosen reel target drives how much the pipeline asks for;
 * the floor below only stops an early trim from throwing away what was
 * already downloaded.
 *
 * Section id: "reels".
 */
object ReelsVault : SectionVault(
    section = "reels",
    dirName = "reels",
    keepFloor = 250,
    videoRequired = true
)
