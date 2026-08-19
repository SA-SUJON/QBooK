package org.qbook.offline

/**
 * The home feed's own offline system.
 *
 * One of three independent vaults. Home keeps its cards in
 * `offline_vaults/home/items.json` and their photos and videos in
 * `offline_vaults/home/media/`. Its count comes from those files and
 * nowhere else: a post counts when every picture it shows is really on
 * disk, and exactly the counted posts are what the offline home feed
 * shows - the number and the screen are the same list.
 *
 * Text posts count the moment they are stored: there is nothing left to
 * download for them.
 *
 * Section id: "feed" (the name the rest of the app already uses).
 */
object HomeVault : SectionVault(
    section = "feed",
    dirName = "home",
    keepFloor = 500,
    videoRequired = false
)
