package xyz.shuncongci.leica

/** One 24-unit outline vocabulary. No Android dependency: keys/paths are regression-testable. */
object WorkspaceIcons {
    data class Spec(val outline:String,val solid:String="")
    val catalog:Map<String,Spec> = linkedMapOf(
        "PLAY" to Spec("M8 4 L21 12 L8 20 Z"),
        "PAUSE" to Spec("M7 4 H10 V20 H7 Z M15 4 H18 V20 H15 Z"),
        "MONITOR" to Spec("M3 13 H7 L10 6 L14 18 L17 11 H21"),
        "PARAMETERS" to Spec("M4 7 H8 M12 7 H20 M4 17 H13 M17 17 H20 M8 4 V10 M13 14 V20 M8 7 H12 M13 17 H17"),
        "MORE" to Spec("", "M5 10 A2 2 0 1 0 5 14 A2 2 0 1 0 5 10 Z M12 10 A2 2 0 1 0 12 14 A2 2 0 1 0 12 10 Z M19 10 A2 2 0 1 0 19 14 A2 2 0 1 0 19 10 Z"),
        "IMAGE" to Spec("M5 4 H19 Q21 4 21 6 V18 Q21 20 19 20 H5 Q3 20 3 18 V6 Q3 4 5 4 Z M3 16 L9 10 L14 15 L17 12 L21 16 M16 7 H17"),
        "GRAY" to Spec("M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z", "M12 3 A9 9 0 0 0 12 21 Z"),
        "DEPTH" to Spec("M3 15 C3 6 8 3 13 4 C20 4 22 9 20 15 C18 21 11 22 6 19 M7 16 C5 11 8 7 12 8 C17 8 18 13 15 17 M10 13 Q12 10 14 13 Q14 16 12 16"),
        "SPACE" to Spec("M12 3 L21 8 V17 L12 22 L3 17 V8 Z M3 8 L12 13 L21 8 M12 13 V22"),
        "RESULT" to Spec("M19 12 V6 Q19 4 17 4 H5 Q3 4 3 6 V18 Q3 20 5 20 H11 M3 16 L8 11 L12 15 M13 18 L16 21 L22 14 M14 8 H15"),
        "COMPARE" to Spec("M5 5 H19 Q21 5 21 7 V17 Q21 19 19 19 H5 Q3 19 3 17 V7 Q3 5 5 5 Z M12 3 V21 M6 14 L9 10 L12 14 M12 14 L16 9 L21 15"),
        "CAMERA" to Spec("M4 7 H8 L10 4 H14 L16 7 H20 Q22 7 22 9 V18 Q22 20 20 20 H4 Q2 20 2 18 V9 Q2 7 4 7 Z M12 10 A4 4 0 1 0 12 18 A4 4 0 1 0 12 10 Z"),
        "EXTERNAL" to Spec("M8 3 V8 M16 3 V8 M5 8 H19 V11 A7 7 0 0 1 12 18 V22 M12 18 A7 7 0 0 1 5 11 V8"),
        "DEVELOP" to Spec("M5 19 L15 9 L18 12 L8 22 Z M4 3 V9 M1 6 H7 M18 2 V6 M16 4 H20 M21 16 V22 M18 19 H23 M13 11 L16 14"),
        "EXPORT" to Spec("M12 16 V3 M7 8 L12 3 L17 8 M4 12 V19 Q4 21 6 21 H18 Q20 21 20 19 V12"),
        "FULLSCREEN" to Spec("M3 9 V3 H9 M15 3 H21 V9 M21 15 V21 H15 M9 21 H3 V15"),
        "COLLAPSE" to Spec("M3 9 H9 V3 M15 3 V9 H21 M21 15 H15 V21 M9 21 V15 H3"),
        "CLOSE" to Spec("M6 6 L18 18 M18 6 L6 18"),
        "STOP" to Spec("M12 2 A10 10 0 1 0 12 22 A10 10 0 1 0 12 2 Z M8 8 H16 V16 H8 Z"),
        "FOLDER" to Spec("M3 7 V19 Q3 21 5 21 H19 Q21 21 21 19 V8 Q21 6 19 6 H12 L9 3 H5 Q3 3 3 5 V7 Z"),
        "BELL" to Spec("M5 17 H19 C16 14 19 5 12 5 C5 5 8 14 5 17 Z M9 20 Q12 23 15 20 M12 3 V5"),
        "SHIELD" to Spec("M12 3 L20 6 V12 Q20 18 12 22 Q4 18 4 12 V6 Z M8 12 L11 15 L16 9"),
        "LOCATION" to Spec("M12 22 C9 18 5 14 5 10 A7 7 0 0 1 19 10 C19 14 15 18 12 22 Z M12 7 A3 3 0 1 0 12 13 A3 3 0 1 0 12 7 Z"),
        "LOCK" to Spec("M6 10 H18 Q20 10 20 12 V19 Q20 21 18 21 H6 Q4 21 4 19 V12 Q4 10 6 10 Z M8 10 V7 A4 4 0 0 1 16 7 V10 M12 14 V17"),
        "OUTPUT" to Spec("M4 4 H20 V20 H4 Z M7 8 H17 M7 12 H12 M7 16 H15"),
        "ORIGINAL" to Spec("M7 3 H20 V17 H7 Z M3 7 V21 H16 M10 12 L13 9 L17 13"),
        "WATERMARK" to Spec("M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M8 15 V9 H10 M8 12 H11 M14 9 V15 H17"),
        "CHEVRON" to Spec("M9 5 L16 12 L9 19"),
        "CHEVRON_DOWN" to Spec("M6 9 L12 15 L18 9"),
        "CHEVRON_UP" to Spec("M6 15 L12 9 L18 15"),
        "BACK" to Spec("M14 5 L7 12 L14 19 M7 12 H21"),
        "INFO" to Spec("M12 2 A10 10 0 1 0 12 22 A10 10 0 1 0 12 2 Z M12 11 V17 M12 7 V7.2"),
        "PALETTE" to Spec("M12 3 A9 9 0 1 0 12 21 H14 Q17 21 15 18 Q13 15 17 14 H19 Q22 14 21 10 Q20 3 12 3 Z M7 9 H7.2 M11 6 H11.2 M16 7 H16.2 M6 14 H6.2"),
        "FILE" to Spec("M5 3 H14 L20 9 V21 H5 Z M14 3 V9 H20 M8 13 H16 M8 17 H14"),
        "OVERLAY" to Spec("M3 3 H17 V7 M3 3 V17 H7 M8 8 H21 V21 H8 Z M8 12 H21"),
        "CLOCK" to Spec("M12 3 A9 9 0 1 0 12 21 A9 9 0 1 0 12 3 Z M12 7 V12 L16 15"),
        "RESTORE" to Spec("M3 10 A9 9 0 1 1 4 18 M3 4 V10 H9 M12 7 V12 L16 14"),
        "CROP" to Spec("M7 2 V17 H22 M2 7 H17 V22 M7 7 H17 V17 M3 21 L5 19 M19 5 L21 3"),
        "FLOW" to Spec("M4 4 H10 V10 H4 Z M14 14 H20 V20 H14 Z M10 7 H17 V14 M7 10 V17 H14")
    )
    fun spec(key:String)=requireNotNull(catalog[key]){"Unknown workspace icon: $key"}
    fun viewer(key:String)=when(key){"ORIGINAL"->"IMAGE";"GRAY"->"GRAY";"THERMAL"->"DEPTH";"RELIEF"->"SPACE";"RESULT"->"RESULT";"COMPARE"->"COMPARE";else->error("Unknown viewer: $key")}
}
