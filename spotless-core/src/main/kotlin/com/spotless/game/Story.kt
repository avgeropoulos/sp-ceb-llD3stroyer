package com.spotless.game

enum class Hair { BUN, PONYTAIL, PIGTAILS, DOWN, WAVY, UPDO }

enum class Cut { HOODIE, TEE_JEANS, SUNDRESS, BLOUSE_SKIRT, CHEER, COCKTAIL, BIKINI, GOWN, GALA }

/** One of Bella's outfits. Each new affection level unlocks a nicer one. */
class Outfit(
    val name: String,
    val cut: Cut,
    val hair: Hair,
    val main: Int,
    val second: Int,
    val shoes: Int,
    val lips: Int,
    val sparkles: Boolean = false,
    val necklace: Boolean = false,
    val earrings: Boolean = false,
    val tiara: Boolean = false,
    val choker: Boolean = false,
)

/** An affection level: what Bella thinks of you, what she wears and what she gives you. */
class Level(
    val title: String,
    val hearts: Int,
    val outfit: Outfit,
    val reward: String,
    val perk: String,
    val revealLine: String,
    val chat: List<String>,
    val thanks: List<String>,
    val meh: List<String>,
)

object Story {
    const val NAME = "Bella"

    val levels = listOf(
        Level(
            title = "New Roommate", hearts = 0,
            outfit = Outfit("Comfy Hoodie", Cut.HOODIE, Hair.BUN,
                rgb(0x9A, 0xA4, 0xB5), rgb(0x6E, 0x77, 0x88), rgb(0xF2, 0xF2, 0xF2), rgb(0xE0, 0x96, 0x96)),
            reward = "", perk = "",
            revealLine = "",
            chat = listOf(
                "So you're the new roommate? Hmm. Cute.",
                "This place is a mess... clean it up and maybe I'll dress up for you.",
                "A guy who cleans? Show me, and I might just show you something nice.",
            ),
            thanks = listOf("Ooh, look at you go. I like a guy who's handy.", "Not bad... you're getting my attention.", "Keep that up and I'll have to change into something nicer."),
            meh = listOf("It's still kind of messy...", "Did you even try?"),
        ),
        Level(
            title = "Flirty", hearts = 2,
            outfit = Outfit("Crop Top & Cutoffs", Cut.TEE_JEANS, Hair.PONYTAIL,
                rgb(0xFF, 0xFF, 0xFF), rgb(0x4A, 0x6F, 0xB5), rgb(0xFF, 0x7A, 0x9C), rgb(0xE8, 0x80, 0x8A)),
            reward = "Fresh lemonade!", perk = "+5 seconds in every room",
            revealLine = "I made us some lemonade! You've earned it.",
            chat = listOf(
                "Like my new top? I picked it with you in mind.",
                "Movie night? I'll save you the spot right next to me.",
                "You keep staring... I don't mind. *winks*",
            ),
            thanks = listOf("Mmm, a clean house and a cute roommate.", "You're spoiling me. Come get your reward... a hug!", "*winks* That's my favourite roommate."),
            meh = listOf("Hey, you missed a few spots!", "Almost! Want to try again?"),
        ),
        Level(
            title = "Fond of You", hearts = 4,
            outfit = Outfit("Short Sundress", Cut.SUNDRESS, Hair.DOWN,
                rgb(0xFF, 0xD9, 0x4F), rgb(0xFF, 0xFF, 0xFF), rgb(0xC9, 0x8E, 0x5A), rgb(0xF0, 0x6E, 0x80)),
            reward = "Homemade cookies!", perk = "+25% coins",
            revealLine = "I baked cookies just for you. And this dress... do you like it? *twirls*",
            chat = listOf(
                "I wore this dress because it's sunny... and because you're here.",
                "Every time I come home it's sparkling. How will I ever repay you? *bites lip*",
                "Walk me to the park later? I want everyone to see us together.",
            ),
            thanks = listOf("You're so sweet. Come here... *kisses your cheek*", "I love coming home to you.", "Aww, you did it again, handsome!"),
            meh = listOf("Hmm, a little messy still. That's okay!", "Tomorrow will be better, right?"),
        ),
        Level(
            title = "Crushing on You", hearts = 6,
            outfit = Outfit("Knotted Blouse & Mini", Cut.BLOUSE_SKIRT, Hair.WAVY,
                rgb(0xFF, 0x9E, 0xBE), rgb(0x2E, 0x2A, 0x38), rgb(0x2E, 0x2A, 0x38), rgb(0xE8, 0x4A, 0x6A),
                earrings = true),
            reward = "She cooks you dinner!", perk = "Bella helps: +20% cleaning power",
            revealLine = "I cooked us dinner tonight... and dressed up a little. Like what you see?",
            chat = listOf(
                "I couldn't stop thinking about you at work today. Not even a little.",
                "This blouse has a few buttons undone... oops. *winks*",
                "When you look at me like that, I forget what I was saying.",
            ),
            thanks = listOf("You're kind of perfect, you know that?", "My hero! *pulls you close*", "Mmm, how are you so good with your hands?"),
            meh = listOf("It's okay, I still like you!", "Let me help you next time?"),
        ),
        Level(
            title = "Cheering for You", hearts = 8,
            outfit = Outfit("Game Day Cheerleader", Cut.CHEER, Hair.PIGTAILS,
                rgb(0xE8, 0x1E, 0x46), rgb(0xFF, 0xFF, 0xFF), rgb(0xFF, 0xFF, 0xFF), rgb(0xE0, 0x1E, 0x4A),
                earrings = true, choker = true),
            reward = "She cheers you on", perk = "+5 more seconds in every room",
            revealLine = "Our squad has a game tonight, but I'll be cheering for you. Like the uniform?",
            chat = listOf(
                "Give me a Y! Give me an O! Give me a U! ...That spells my favourite person.",
                "I did a high kick just now. Did you see? *winks*",
                "The choker? A little extra, just for you.",
            ),
            thanks = listOf("Go, go, go! That's my guy!", "*shakes pom-poms* You're the MVP!", "That deserves a victory kiss. *mwah*"),
            meh = listOf("Shake it off, champ! Next round is yours.", "Even MVPs miss sometimes. I still like you."),
        ),
        Level(
            title = "Sweetheart", hearts = 10,
            outfit = Outfit("Strapless Red Mini", Cut.COCKTAIL, Hair.WAVY,
                rgb(0xD8, 0x1B, 0x3C), rgb(0xA0, 0x10, 0x2A), rgb(0xD8, 0x1B, 0x3C), rgb(0xC8, 0x10, 0x30),
                necklace = true, earrings = true),
            reward = "A dinner date!", perk = "+1 extra heart for 3-star rooms",
            revealLine = "I booked us a table tonight. Can you zip me up? ...It's a date.",
            chat = listOf(
                "Our date was the best night I've had in years. Let's not end it yet.",
                "This dress? It's only for you to see.",
                "Come sit closer. I don't bite... much. *winks*",
                "I keep finding excuses to be in the same room as you.",
            ),
            thanks = listOf("Come here, you! *wraps her arms around you*", "That deserves a kiss. *mwah*", "My sweetheart did it again!"),
            meh = listOf("Don't worry about it, cutie.", "We'll clean the rest together later!"),
        ),
        Level(
            title = "In Love", hearts = 13,
            outfit = Outfit("Pool Party Bikini", Cut.BIKINI, Hair.WAVY,
                rgb(0xFF, 0x3E, 0x8A), rgb(0xFF, 0xB8, 0xD8), rgb(0xFF, 0x3E, 0x8A), rgb(0xD8, 0x20, 0x50),
                necklace = true, earrings = true),
            reward = "A pool party for two", perk = "+50% more coins",
            revealLine = "Pool party, just you and me. Rub some sunscreen on my back? ...I think I'm falling for you.",
            chat = listOf(
                "I love you. There, I said it!",
                "Feel how fast my heart is beating? That's all you.",
                "Hold me closer... *blows you a kiss*",
                "I can't take my eyes off you. Don't make me wait too long.",
            ),
            thanks = listOf("I love you so much! *kisses you*", "You're my everything!", "*blows you a kiss* Perfect, as always."),
            meh = listOf("I love you anyway!", "Who cares about a little mess? I have you."),
        ),
        Level(
            title = "Soulmates", hearts = 17,
            outfit = Outfit("Golden Slit Gown", Cut.GOWN, Hair.UPDO,
                rgb(0xE8, 0xB8, 0x48), rgb(0xC0, 0x90, 0x30), rgb(0xE8, 0xB8, 0x48), rgb(0xD0, 0x20, 0x48),
                sparkles = true, necklace = true, earrings = true, tiara = true),
            reward = "Her date to the Royal Gala", perk = "Double coins!",
            revealLine = "Will you be my date to the Royal Gala? Forever and always.",
            chat = listOf(
                "Being with you is my favourite thing in the world.",
                "Every head turns when I walk in. But I only look at you.",
                "I still can't believe you're mine. *kisses you*",
                "Come here, you. I've missed you all day.",
            ),
            thanks = listOf("My soulmate! *twirls*", "Our home is perfect. Like us.", "I'm the luckiest girl ever!"),
            meh = listOf("Nothing could ruin my mood today!", "Messy or not, I adore you."),
        ),
    )

    val maxLevel get() = levels.size - 1

    /** The affection level reached with this many hearts. */
    fun levelFor(hearts: Int): Int = levels.indexOfLast { hearts >= it.hearts }.coerceAtLeast(0)
}
