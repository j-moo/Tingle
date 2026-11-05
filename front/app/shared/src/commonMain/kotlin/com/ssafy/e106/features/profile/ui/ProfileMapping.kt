package com.ssafy.e106.features.profile.ui

import com.ssafy.e106.features.profile.normalizeProfileNickname

internal fun ProfileGender.code() = when (this) { ProfileGender.Male -> "MALE"; ProfileGender.Female -> "FEMALE" }
internal fun ProfileSmoking.code() = when (this) {
    ProfileSmoking.NonSmoker -> "NON_SMOKER"
    ProfileSmoking.Electronic -> "E_CIGARETTE"
    ProfileSmoking.OccasionallyWhenDrinking -> "ONLY_WHEN_DRINKING"
    ProfileSmoking.Smoker -> "SMOKER"
}
internal fun ProfileDrinking.code() = when (this) {
    ProfileDrinking.Never -> "NEVER"
    ProfileDrinking.SpecialOccasions -> "SPECIAL_OCCASIONS_ONLY"
    ProfileDrinking.MonthlyOrLess -> "MONTHLY_AT_MOST_ONCE"
    ProfileDrinking.MonthlyTwoToFour -> "MONTHLY_TWO_TO_FOUR"
    ProfileDrinking.WeeklyTwoToThree -> "WEEKLY_TWO_TO_THREE"
    ProfileDrinking.WeeklyFourOrMore -> "WEEKLY_FOUR_OR_MORE"
}
internal fun String?.religion() = when (this) {
    "NONE" -> ProfileReligion.None
    "PROTESTANT" -> ProfileReligion.Protestant
    "CATHOLIC" -> ProfileReligion.Catholic
    "BUDDHIST" -> ProfileReligion.Buddhist
    "OTHER" -> ProfileReligion.Other
    "UNDISCLOSED" -> ProfileReligion.Private
    else -> null
}
internal fun ProfileReligion.code() = when (this) {
    ProfileReligion.None -> "NONE"
    ProfileReligion.Protestant -> "PROTESTANT"
    ProfileReligion.Catholic -> "CATHOLIC"
    ProfileReligion.Buddhist -> "BUDDHIST"
    ProfileReligion.Other -> "OTHER"
    ProfileReligion.Private -> "UNDISCLOSED"
}
internal fun validNickname(value: String) = Regex("[가-힣a-zA-Z0-9 ]{2,20}").matches(normalizeProfileNickname(value))
internal fun validBirthDate(digits: String): Boolean {
    if (!Regex("[0-9]{8}").matches(digits)) return false
    val y = digits.take(4).toInt()
    val m = digits.substring(4, 6).toInt()
    val d = digits.takeLast(2).toInt()
    if (y < 1900 || m !in 1..12) return false
    val days = when (m) { 2 -> if (y % 400 == 0 || (y % 4 == 0 && y % 100 != 0)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    return d in 1..days // Current age/future dates are validated against the server's Korean date.
}
internal fun isoBirthDate(digits: String) = "${digits.take(4)}-${digits.substring(4, 6)}-${digits.takeLast(2)}"
