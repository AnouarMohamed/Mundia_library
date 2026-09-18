package com.mundiapolis.library.catalog.dto

data class Author(
    val id: String,
    val name: String,
    val bio: String? = null
)
