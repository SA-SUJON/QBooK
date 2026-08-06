package com.dustbook.app.offline

/**
 * The stories' own offline system.
 *
 * Stories of the user's followed friends, watched and unwatched alike,
 * with their photo or video in `offline_vaults/stories/media/`. One story
 * is one photo or one clip, so its count rule comes out simple: the
 * moment that file is complete on disk, the story counts and appears in
 * the offline story viewer - not before.
 *
 * Section id: "stories".
 */
object StoriesVault : SectionVault(
    section = "stories",
    dirName = "stories",
    keepFloor = 200,
    videoRequired = false
)
