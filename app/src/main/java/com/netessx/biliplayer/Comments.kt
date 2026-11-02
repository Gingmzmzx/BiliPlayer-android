package com.netessx.biliplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.json.JSONTokener

/** 从视频页 DOM 读到的一条评论。 */
internal data class VideoComment(
    val id: String,
    val user: String,
    val avatar: String,
    val message: String,
    val time: String,
    val like: String,
    val replyCount: Int,
    val replies: List<VideoComment> = emptyList(),
)

internal data class CommentsSnapshot(
    val total: Int,
    val comments: List<VideoComment>,
)

/**
 * 评论 DOM 读取器。
 *
 * B 站评论使用 `bili-comments` Web Component + Shadow DOM：
 *   bili-comments
 *     bili-comment-thread-renderer
 *       bili-comment-renderer
 *         bili-comment-user-info / bili-rich-text / action-buttons
 *       bili-comment-replies-renderer
 *
 * 这里只做滚动触发懒加载并读取现存 DOM，不请求评论接口。
 */
internal object CommentDom {

    /**
     * 把评论区滚进视口触发懒加载。
     * 每次「加载更多」从评论区顶部开始，并比上次多滚一屏；
     * 首次调用会记住原滚动位置，[RESTORE_JS] 负责恢复，避免影响视频画面。
     */
    val PREPARE_JS = """
        (function(){
          var host = document.querySelector('bili-comments');
          if (!host) return 'no-host';
          try {
            if (window.__biliCommentPrevScroll == null) {
              window.__biliCommentPrevScroll = window.scrollY || 0;
            }
            var step = window.__biliCommentStep || 0;
            var hostTop = host.getBoundingClientRect().top + (window.scrollY || window.pageYOffset || 0);
            // 逐步滚过视频区，逼近/越过评论区，触发 B 站评论的 IntersectionObserver 懒加载
            var y = Math.max(0, hostTop - 120 + step * 700);
            window.scrollTo(0, y);
            try { document.documentElement.scrollTop = y; } catch(e) {}
            try { document.body.scrollTop = y; } catch(e) {}
            window.dispatchEvent(new Event('scroll'));
            window.__biliCommentStep = step + 1;
          } catch(e) {}
          return 'ok';
        })()
    """.trimIndent()

    /** 读取完成后恢复原来的滚动位置。 */
    val RESTORE_JS = """
        (function(){
          try {
            if (window.__biliCommentPrevScroll != null) {
              window.scrollTo(0, window.__biliCommentPrevScroll);
            }
          } catch(e) {}
          return 'ok';
        })()
    """.trimIndent()

    /** 重新打开抽屉时回到评论区顶部。 */
    val RESET_JS = """
        (function(){
          try {
            window.__biliCommentStep = 0;
            window.__biliCommentPrevScroll = null;
          } catch(e) {}
          return 'ok';
        })()
    """.trimIndent()

    /** 同步遍历 Shadow DOM，返回 JSON 字符串。 */
    val EXTRACT_JS = """
        (function(){
          try {
            var host = document.querySelector('bili-comments');
            if (!host || !host.shadowRoot) return JSON.stringify({ total: 0, items: [] });
            var root = host.shadowRoot;
            function text(el){
              return el ? String(el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim() : '';
            }
            var header = root.querySelector('bili-comments-header-renderer');
            var hroot = header && header.shadowRoot;
            var total = parseInt(text(hroot && hroot.querySelector('#count')).replace(/[^\d]/g, ''), 10) || 0;

            function pick(thread, isReply){
              var sr = thread.shadowRoot;
              var renderer = isReply ? thread : (sr && sr.querySelector('bili-comment-renderer'));
              var body = renderer && renderer.shadowRoot;
              if (!body) return null;
              var info = body.querySelector('bili-comment-user-info');
              var iroot = info && info.shadowRoot;
              var user = text(iroot && iroot.querySelector('#user-name'));
              var avatar = '';
              var img = body.querySelector('#user-avatar img') || (iroot && iroot.querySelector('#user-avatar img'));
              if (!img) {
                var avatarEl = body.querySelector('bili-avatar');
                var aroot = avatarEl && avatarEl.shadowRoot;
                img = aroot && aroot.querySelector('img');
                if (!img && aroot) {
                  var layer = aroot.querySelector('.layer-res');
                  var bg = layer ? getComputedStyle(layer).backgroundImage : '';
                  var match = /url\(["']?([^"')]+)/.exec(bg || '');
                  if (match) avatar = match[1];
                }
              }
              if (img && img.src) avatar = img.src;
              if (avatar.indexOf('//') === 0) avatar = 'https:' + avatar;

              var rich = body.querySelector('bili-rich-text');
              var contents = rich && rich.shadowRoot && rich.shadowRoot.querySelector('#contents');
              var message = text(contents);
              if (!message) return null;

              var actions = body.querySelector('bili-comment-action-buttons-renderer');
              var aroot2 = actions && actions.shadowRoot;
              var time = text(aroot2 && aroot2.querySelector('#pubdate'));
              var like = text(aroot2 && aroot2.querySelector('#like #count'));

              var repliesHost = sr && sr.querySelector('bili-comment-replies-renderer');
              var rroot = repliesHost && repliesHost.shadowRoot;
              var replyEls = rroot ? Array.prototype.slice.call(rroot.querySelectorAll('bili-comment-reply-renderer')) : [];
              var replies = replyEls.map(function(r){ return pick(r, true); }).filter(Boolean);
              var replyText = text(rroot);
              var replyMatch = /(\d+)/.exec(replyText);
              var replyCount = replyMatch ? parseInt(replyMatch[1], 10) : replies.length;

              return {
                id: user + '|' + time + '|' + message.slice(0, 80),
                user: user || '匿名用户',
                avatar: avatar,
                message: message,
                time: time,
                like: like,
                replyCount: replyCount,
                replies: replies
              };
            }

            var threads = Array.prototype.slice.call(root.querySelectorAll('bili-comment-thread-renderer'));
            var items = threads.map(function(t){ return pick(t, false); }).filter(Boolean);
            return JSON.stringify({ total: total, items: items });
          } catch (e) {
            return JSON.stringify({ total: 0, items: [], error: String(e) });
          }
        })()
    """.trimIndent()

    fun parse(raw: String?): CommentsSnapshot {
        if (raw.isNullOrBlank() || raw == "null") return CommentsSnapshot(0, emptyList())
        var value = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        if (value is String) {
            value = runCatching { JSONTokener(value).nextValue() }.getOrNull()
        }
        val obj = value as? JSONObject ?: return CommentsSnapshot(0, emptyList())
        val total = obj.optInt("total")
        val arr = obj.optJSONArray("items") ?: return CommentsSnapshot(total, emptyList())
        val list = buildList {
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { add(parseComment(it)) }
            }
        }
        return CommentsSnapshot(total, list)
    }

    private fun parseComment(obj: JSONObject): VideoComment {
        val repliesArr = obj.optJSONArray("replies")
        return VideoComment(
            id = obj.optString("id"),
            user = obj.optString("user").ifBlank { "匿名用户" },
            avatar = obj.optString("avatar"),
            message = obj.optString("message"),
            time = obj.optString("time"),
            like = obj.optString("like"),
            replyCount = obj.optInt("replyCount"),
            replies = buildList {
                if (repliesArr != null) {
                    for (i in 0 until repliesArr.length()) {
                        repliesArr.optJSONObject(i)?.let { add(parseComment(it)) }
                    }
                }
            },
        )
    }
}

/** 播放页评论抽屉：显示当前视频页已加载的评论 DOM。 */
@Composable
internal fun CommentsSheet(
    bvid: String,
    onClose: () -> Unit,
) {
    val cache by PlayerController.commentCache.collectAsState()
    val cached = cache[bvid]
    var comments by remember(bvid) { mutableStateOf(cached?.comments.orEmpty()) }
    var total by remember(bvid) { mutableStateOf(cached?.total ?: 0) }
    var loading by remember(bvid) { mutableStateOf(false) }
    var loadedOnce by remember(bvid) { mutableStateOf(cached != null) }

    fun load(append: Boolean) {
        if (loading) return
        if (append && comments.isNotEmpty() && comments.size >= total && total > 0) return
        loading = true
        PlayerController.loadComments(bvid = bvid, reset = !append) { snapshot ->
            comments = if (append) {
                val known = comments.mapTo(HashSet()) { it.id }
                comments + snapshot.comments.filterNot { it.id in known }
            } else {
                snapshot.comments
            }
            total = snapshot.total
            loading = false
            loadedOnce = true
        }
    }

    LaunchedEffect(bvid) {
        val ready = cache[bvid]
        if (ready != null) {
            comments = ready.comments
            total = ready.total
            loadedOnce = true
        } else {
            load(false)
        }
    }
    // 静默预取可能在抽屉打开期间完成，完成后直接替换为完整结果。
    LaunchedEffect(cache[bvid]) {
        val ready = cache[bvid] ?: return@LaunchedEffect
        if (!loading) {
            comments = ready.comments
            total = ready.total
            loadedOnce = true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                BasicText(
                    "评论",
                    style = TextStyle(
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                BasicText(
                    if (total > 0) "$total 条评论" else "按热度排序",
                    style = TextStyle(color = TextMuted, fontSize = 10.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Pill("关闭", onClick = onClose)
        }

        Spacer(Modifier.height(10.dp))

        when {
            loading && comments.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                BasicText("正在读取评论…", style = TextStyle(color = TextMuted, fontSize = 13.sp))
            }

            comments.isEmpty() -> Box(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        if (loadedOnce) "没有读取到评论" else "正在读取评论…",
                        style = TextStyle(color = TextMuted, fontSize = 13.sp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Pill("重新读取") { load(false) }
                }
            }

            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(comments, key = { it.id }) { comment ->
                    CommentRow(comment)
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        when {
                            loading -> BasicText(
                                "正在加载…",
                                style = TextStyle(color = TextMuted, fontSize = 12.sp)
                            )
                            comments.size < total || total == 0 -> Pill("加载更多") { load(true) }
                            else -> BasicText(
                                "没有更多评论了",
                                style = TextStyle(color = TextDim, fontSize = 11.sp)
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: VideoComment) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSoft)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(GlassBorder)
            ) {
                if (comment.avatar.isNotBlank()) {
                    CoverBitmap(comment.avatar, Modifier.fillMaxSize())
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    comment.user,
                    style = TextStyle(
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                BasicText(
                    comment.time,
                    style = TextStyle(color = TextDim, fontSize = 9.sp)
                )
            }
            if (comment.like.isNotBlank() && comment.like != "0") {
                BasicText(
                    "赞 ${comment.like}",
                    style = TextStyle(color = TextMuted, fontSize = 10.sp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        BasicText(
            comment.message,
            style = TextStyle(color = TextPrimary, fontSize = 13.sp, lineHeight = 19.sp)
        )

        if (comment.replies.isNotEmpty() || comment.replyCount > 0) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassBorder.copy(alpha = 0.45f))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                comment.replies.forEach { reply ->
                    Column {
                        BasicText(
                            "${reply.user}：${reply.message}",
                            style = TextStyle(color = TextPrimary, fontSize = 12.sp, lineHeight = 17.sp)
                        )
                        BasicText(
                            reply.time + if (reply.like.isNotBlank() && reply.like != "0") " · 赞 ${reply.like}" else "",
                            style = TextStyle(color = TextDim, fontSize = 9.sp)
                        )
                    }
                }
                val remaining = comment.replyCount - comment.replies.size
                if (remaining > 0) {
                    BasicText(
                        "共 ${comment.replyCount} 条回复",
                        style = TextStyle(color = TextMuted, fontSize = 10.sp)
                    )
                }
            }
        }
    }
}
