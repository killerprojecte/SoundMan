package hk.uwu.soundman.ui.components.apppicker

/**
 * 应用选择器所需的全部文案。
 *
 * 将选择器展示所需的外部文案集中于此，使 [AppPickerContent]、
 * [AppPickerBottomSheet] 和 [AppPickerFullPage] 不直接依赖
 * 具体业务（如黑名单）的字符串资源，由调用方按场景传入。
 *
 * @param title 顶栏标题
 * @param closeActionDescription 关闭按钮的无障碍描述
 * @param saveActionDescription 保存按钮的无障碍描述
 * @param systemAppLabel 系统应用标签文本
 * @param loadingText 加载中提示文本
 * @param emptyText 空列表（搜索无结果）提示文本
 */
data class AppPickerStrings(
    val title: String,
    val closeActionDescription: String,
    val saveActionDescription: String,
    val systemAppLabel: String,
    val loadingText: String,
    val emptyText: String,
)
