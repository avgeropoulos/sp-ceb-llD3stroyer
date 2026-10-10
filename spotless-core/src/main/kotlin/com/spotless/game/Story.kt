package com.spotless.game

enum class Hair { BUN, PONYTAIL, DOWN, WAVY, UPDO }

enum class Cut { HOODIE, TEE_JEANS, SUNDRESS, BLOUSE_SKIRT, COCKTAIL, GOWN, GALA }

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
            outfit = Outfit("Crop Top & Jeans", Cut.TEE_JEANS, Hair.PONYTAIL,
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
            outfit = Outfit("Summer Sundress", Cut.SUNDRESS, Hair.DOWN,
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
            outfit = Outfit("Blouse & Pencil Skirt", Cut.BLOUSE_SKIRT, Hair.WAVY,
                rgb(0xFF, 0x9E, 0xBE), rgb(0x2E, 0x2A, 0x38), rgb(0x2E, 0x2A, 0x38), rgb(0xE8, 0x4A, 0x6A),
                earrings = true),
            reward = "She cooks you dinner!", perk = "Bella helps: +20% cleaning power",
            revealLine = "I cooked us dinner tonight... and dressed up a little. Like what you see?",
            chat = listOf(
                "I couldn't stop thinking about you at work today.",
                "This blouse has one button too many undone... oops. *winks*",
                "You look good today. Then again, you always do.",
            ),
            thanks = listOf("You're kind of perfect, you know that?", "My hero! *pulls you close*", "Mmm, how are you so good with your hands?"),
            meh = listOf("It's okay, I still like you!", "Let me help you next time?"),
        ),
        Level(
            title = "Sweetheart", hearts = 9,
            outfit = Outfit("Little Red Dress", Cut.COCKTAIL, Hair.WAVY,
                rgb(0xD8, 0x1B, 0x3C), rgb(0xA0, 0x10, 0x2A), rgb(0xD8, 0x1B, 0x3C), rgb(0xC8, 0x10, 0x30),
                necklace = true, earrings = true),
            reward = "A dinner date!", perk = "+1 extra heart for 3-star rooms",
            revealLine = "I booked us a table tonight. Can you zip me up? ...It's a date.",
            chat = listOf(
                "Our date was the best night I've had in years. Let's not end it yet.",
                "This little red dress? It's only for you to see.",
                "Come sit closer. I don't bite... much. *winks*",
            ),
            thanks = listOf("Come here, you! *wraps her arms around you*", "That deserves a kiss. *mwah*", "My sweetheart did it again!"),
            meh = listOf("Don't worry about it, cutie.", "We'll clean the rest together later!"),
        ),
        Level(
            title = "In Love", hearts = 12,
            outfit = Outfit("Emerald Evening Gown", Cut.GOWN, Hair.UPDO,
                rgb(0x0E, 0x8A, 0x6A), rgb(0x0A, 0x5E, 0x4A), rgb(0xE8, 0xC8, 0x6A), rgb(0xC4, 0x2A, 0x4E),
                sparkles = true, necklace = true, earrings = true),
            reward = "A slow dance and a kiss on the cheek", perk = "+50% more coins",
            revealLine = "Dance with me, nice and slow... I think I'm falling in love with you.",
            chat = listOf(
                "I love you. There, I said it!",
                "Feel how fast my heart is beating? That's all you.",
                "Hold me closer... *blows you a kiss*",
            ),
            thanks = listOf("I love you so much! *kisses you*", "You're my everything!", "*blows you a kiss* Perfect, as always."),
            meh = listOf("I love you anyway!", "Who cares about a little mess? I have you."),
        ),
        Level(
            title = "Soulmates", hearts = 16,
            outfit = Outfit("Golden Gala Gown", Cut.GALA, Hair.UPDO,
                rgb(0xF2, 0xD8, 0x8C), rgb(0xFF, 0xF4, 0xD6), rgb(0xF2, 0xD8, 0x8C), rgb(0xD0, 0x30, 0x58),
                sparkles = true, necklace = true, earrings = true, tiara = true),
            reward = "Her date to the Royal Gala", perk = "Double coins!",
            revealLine = "Will you be my date to the Royal Gala? Forever and always.",
            chat = listOf(
                "Being with you is my favourite thing in the world.",
                "Every head turns when I walk in. But I only look at you.",
                "I still can't believe you're mine. *kisses you*",
            ),
            thanks = listOf("My soulmate! *twirls*", "Our home is perfect. Like us.", "I'm the luckiest girl ever!"),
            meh = listOf("Nothing could ruin my mood today!", "Messy or not, I adore you."),
        ),
    )

    val maxLevel get() = levels.size - 1

    /** The affection level reached with this many hearts. */
    fun levelFor(hearts: Int): Int = levels.indexOfLast { hearts >= it.hearts }.coerceAtLeast(0)
}
