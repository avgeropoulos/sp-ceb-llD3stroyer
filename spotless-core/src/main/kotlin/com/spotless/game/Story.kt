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
                "Oh. Hey. You're the new roommate, right?",
                "This place is kind of a mess... not that I'm going to clean it.",
                "If you tidy up, maybe I'll actually notice you.",
            ),
            thanks = listOf("Huh. That actually looks nice.", "Okay... not bad. Thanks.", "Wow, you can clean? Noted."),
            meh = listOf("It's still kind of messy...", "Did you even try?"),
        ),
        Level(
            title = "Friendly", hearts = 3,
            outfit = Outfit("Tee & Jeans", Cut.TEE_JEANS, Hair.PONYTAIL,
                rgb(0xFF, 0xFF, 0xFF), rgb(0x4A, 0x6F, 0xB5), rgb(0xFF, 0x7A, 0x9C), rgb(0xE8, 0x80, 0x8A)),
            reward = "Fresh lemonade!", perk = "+5 seconds in every room",
            revealLine = "I made us some lemonade! You've earned it.",
            chat = listOf(
                "You know, you're kind of fun to have around.",
                "Want to watch a movie later? You pick!",
                "My friends asked who my new roommate is. I said you're cool.",
            ),
            thanks = listOf("You're amazing at this!", "It's so much nicer here now. Thank you!", "High five, roomie!"),
            meh = listOf("Hey, you missed a few spots!", "Almost! Want to try again?"),
        ),
        Level(
            title = "Fond of You", hearts = 7,
            outfit = Outfit("Summer Sundress", Cut.SUNDRESS, Hair.DOWN,
                rgb(0xFF, 0xD9, 0x4F), rgb(0xFF, 0xFF, 0xFF), rgb(0xC9, 0x8E, 0x5A), rgb(0xF0, 0x6E, 0x80)),
            reward = "Homemade cookies!", perk = "+25% coins",
            revealLine = "I baked cookies... just for you. Do you like my dress?",
            chat = listOf(
                "I put on this dress because it's sunny. ...And because you're here.",
                "Every time I come home, it's sparkling. You spoil me!",
                "Let's go for a walk in the park sometime?",
            ),
            thanks = listOf("You're so sweet to me!", "I love coming home to this.", "Aww, you did it again!"),
            meh = listOf("Hmm, a little messy still. That's okay!", "Tomorrow will be better, right?"),
        ),
        Level(
            title = "Crushing on You", hearts = 12,
            outfit = Outfit("Blouse & Pencil Skirt", Cut.BLOUSE_SKIRT, Hair.WAVY,
                rgb(0xFF, 0x9E, 0xBE), rgb(0x2E, 0x2A, 0x38), rgb(0x2E, 0x2A, 0x38), rgb(0xE8, 0x4A, 0x6A),
                earrings = true),
            reward = "She cooks you dinner!", perk = "Bella helps: +20% cleaning power",
            revealLine = "I cooked dinner for us tonight. I... dressed up a little.",
            chat = listOf(
                "I keep thinking about you at work. Is that weird?",
                "You look nice today. I mean... you always do.",
                "I told my sister about you. She says I'm blushing. I'm not!",
            ),
            thanks = listOf("You're kind of perfect, you know that?", "My hero! *blushes*", "How are you so good at everything?"),
            meh = listOf("It's okay, I still like you!", "Let me help you next time?"),
        ),
        Level(
            title = "Sweetheart", hearts = 18,
            outfit = Outfit("Red Cocktail Dress", Cut.COCKTAIL, Hair.WAVY,
                rgb(0xD8, 0x1B, 0x3C), rgb(0xA0, 0x10, 0x2A), rgb(0xD8, 0x1B, 0x3C), rgb(0xC8, 0x10, 0x30),
                necklace = true, earrings = true),
            reward = "A dinner date!", perk = "+1 extra heart for 3-star rooms",
            revealLine = "I booked us a table tonight. It's a date! Do I look okay?",
            chat = listOf(
                "Our date was the best night I've had in years.",
                "I can't stop smiling when you're around.",
                "Is it too soon to call you my sweetheart?",
            ),
            thanks = listOf("Come here, you! *hugs*", "You make this house feel like home.", "My sweetheart did it again!"),
            meh = listOf("Don't worry about it, cutie.", "We'll clean the rest together later!"),
        ),
        Level(
            title = "In Love", hearts = 25,
            outfit = Outfit("Emerald Evening Gown", Cut.GOWN, Hair.UPDO,
                rgb(0x0E, 0x8A, 0x6A), rgb(0x0A, 0x5E, 0x4A), rgb(0xE8, 0xC8, 0x6A), rgb(0xC4, 0x2A, 0x4E),
                sparkles = true, necklace = true, earrings = true),
            reward = "A slow dance and a kiss on the cheek", perk = "+50% more coins",
            revealLine = "Dance with me? ...I think I'm falling in love with you.",
            chat = listOf(
                "I love you. There, I said it!",
                "Every song on the radio is about you now.",
                "You make my heart race. *kisses your cheek*",
            ),
            thanks = listOf("I love you so much!", "You're my everything!", "*kisses your cheek* Perfect, as always."),
            meh = listOf("I love you anyway!", "Who cares about a little mess? I have you."),
        ),
        Level(
            title = "Soulmates", hearts = 33,
            outfit = Outfit("Golden Gala Gown", Cut.GALA, Hair.UPDO,
                rgb(0xF2, 0xD8, 0x8C), rgb(0xFF, 0xF4, 0xD6), rgb(0xF2, 0xD8, 0x8C), rgb(0xD0, 0x30, 0x58),
                sparkles = true, necklace = true, earrings = true, tiara = true),
            reward = "Her date to the Royal Gala", perk = "Double coins!",
            revealLine = "Will you be my date to the Royal Gala? Forever and always.",
            chat = listOf(
                "Being with you is my favourite thing in the world.",
                "We make the best team. In cleaning and in love!",
                "I still can't believe you're mine.",
            ),
            thanks = listOf("My soulmate! *twirls*", "Our home is perfect. Like us.", "I'm the luckiest girl ever!"),
            meh = listOf("Nothing could ruin my mood today!", "Messy or not, I adore you."),
        ),
    )

    val maxLevel get() = levels.size - 1

    /** The affection level reached with this many hearts. */
    fun levelFor(hearts: Int): Int = levels.indexOfLast { hearts >= it.hearts }.coerceAtLeast(0)
}
