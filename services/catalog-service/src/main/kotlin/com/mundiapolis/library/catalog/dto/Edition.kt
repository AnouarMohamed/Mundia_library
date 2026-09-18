package com.mundiapolis.library.catalog.dto

data class Edition(
    val editionId: String,
    val workId: String,
    val title: String,
    val isbn: String,
    val publisher: String,
    val publicationYear: Int,
    val language: String,
    val pageCount: Int,
    val coverUrl: String? = null,
    val coverColor: String? = null,
    val videoUrl: String? = null,
    val totalCopies: Int,
    val availableCopies: Int,
    val isActive: Boolean
)
