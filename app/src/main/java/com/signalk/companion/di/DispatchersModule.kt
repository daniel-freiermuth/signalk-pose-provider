package com.signalk.companion.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/** Dispatcher for blocking I/O: sockets, HTTP, DNS. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU-bound work and general background coordination. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/**
 * Provides coroutine dispatchers so classes receive them instead of hard-coding
 * [Dispatchers], which lets tests substitute a test dispatcher.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    // The injection root is the one place allowed to name a concrete dispatcher.
    @Suppress("InjectDispatcher")
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    // The injection root is the one place allowed to name a concrete dispatcher.
    @Suppress("InjectDispatcher")
    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
