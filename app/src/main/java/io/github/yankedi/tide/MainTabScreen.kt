package io.github.yankedi.tide


import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun T_tab(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    isEditing: Boolean,
    onTitleChange: (String) -> Unit,
    onEditDone: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var textFieldValue: TextFieldValue by remember(isEditing) {
        mutableStateOf(TextFieldValue(text = title, selection = TextRange(title.length)))
    }
    LaunchedEffect(isEditing) {
        if (isEditing) {
            focusRequester.requestFocus()
            delay(100)
            keyboardController?.show()
        }
    }

    val textStyle = LocalTextStyle.current.copy(
        color = if (isEditing) MaterialTheme.colorScheme.primary
        else if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        lineHeight = MaterialTheme.typography.bodyLarge.fontSize
    )

    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(13.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.width(IntrinsicSize.Max), contentAlignment = Alignment.Center) {
            Text(
                text = title.ifEmpty { " " },
                style = textStyle,
                modifier = Modifier.alpha(if (isEditing) 0f else 1f),
                maxLines = 1
            )

            if (isEditing) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = {
                        textFieldValue = it
                        onTitleChange(it.text)
                    },
                    singleLine = true,
                    textStyle = textStyle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        onEditDone()
                        keyboardController?.hide()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        }
    }
}
data class TabModel(
    val id: Int,
    var title: String
)

class TabViewModel : ViewModel() {
    val tabs = mutableStateListOf(
        TabModel(id = 0, title = "Local")
    )

    fun addTab() {
        val newId = if (tabs.isEmpty()) 0 else tabs.maxOf { it.id } + 1
        tabs.add(TabModel(id = newId, title = "新标签 $newId"))
    }
    fun removeTab(tabId: Int) {
        tabs.removeAll { it.id == tabId }
    }
    fun renameTab(tabId: Int, newTitle: String) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index != -1) {
            tabs[index] = tabs[index].copy(title = newTitle)
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable

fun DynmicTab(tabViewModel: TabViewModel = viewModel()) {
    val tabs = tabViewModel.tabs

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { tabs.size })

    Scaffold(
        topBar = {
            if (tabs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    tabs.forEachIndexed { index, tabModel ->
                        var isEditing by remember { mutableStateOf(false) }
                        var editName by remember { mutableStateOf(tabModel.title) }
                        var expanded by remember { mutableStateOf(false) }
                        Box{
                            val terminalViewModel: TerminalViewModel = viewModel(key = "terminal_${tabModel.id}")
                            T_tab(
                                title = if (isEditing) editName else tabModel.title,
                                selected = pagerState.currentPage == index,
                                isEditing = isEditing,
                                onTitleChange = { editName = it },
                                onEditDone = {
                                    tabViewModel.renameTab(tabModel.id, editName)
                                    isEditing = false
                                },
                                onClick = {
                                    if (!isEditing) { // 正在编辑时点击不触发滚动
                                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                },
                                onLongClick = { expanded = true }
                            )
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = {Text("Kill")},
                                    onClick = {
                                        tabViewModel.removeTab(tabModel.id)
                                        terminalViewModel.killTerminal()
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = {
                                        expanded = false
                                        editName = tabModel.title
                                        isEditing = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (tabs.isNotEmpty()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    key = { index -> tabs[index].id }
                ) { pageIndex ->
                    // 根据数据模型中的 ID，在此处动态实例化 Terminal 及其 ViewModel
                    val currentTab = tabs[pageIndex]
                    val terminalViewModel: TerminalViewModel = viewModel(key = "terminal_${currentTab.id}")
                    Terminal(viewModel = terminalViewModel)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("请点击右上角添加标签")
                }
            }
        }
    }
}
