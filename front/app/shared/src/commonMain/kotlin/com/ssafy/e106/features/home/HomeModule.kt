package com.ssafy.e106.features.home

import com.ssafy.e106.features.home.ui.HomeSessionViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val homeModule = module { viewModel { HomeSessionViewModel(get(), get(), get(), get(), get(), get(), get()) } }
