package com.mundiapolis.library.catalog.dto

data class Work(
    val workId: String,
    val title: String,
    val summary: String,
    val description: String,
    val genre: String,
    val rating: Double,
    val authors: List<Author>
)
