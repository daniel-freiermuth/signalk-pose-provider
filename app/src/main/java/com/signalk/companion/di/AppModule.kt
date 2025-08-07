package com.signalk.companion.di

import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SignalKTransmitter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideLocationService(): LocationService {
        return LocationService()
    }
    
    @Provides
    @Singleton
    fun provideSignalKTransmitter(): SignalKTransmitter {
        return SignalKTransmitter()
    }
}
