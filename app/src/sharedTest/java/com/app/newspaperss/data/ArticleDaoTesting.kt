package com.app.newspaperss.data

/** [ArticleDao.insertFetched], each article fetched from its own link: how most tests store articles. */
suspend fun ArticleDao.insertNew(articles: List<ArticleEntity>): Int = insertFetched(articles.map { it to it.url })
