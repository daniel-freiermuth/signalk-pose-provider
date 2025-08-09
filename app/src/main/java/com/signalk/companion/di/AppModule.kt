package com.signalk.companion.di

import android.content.Context
import com.signalk.companion.service.AuthenticationService
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.service.SignalKTransmitter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideAuthenticationService(): AuthenticationService {
        return AuthenticationService()
    }
    
    @Provides
    @Singleton
    fun provideLocationService(): LocationService {
        return LocationService()
    }
    
    @Provides
    @Singleton
    fun provideSensorService(
        @ApplicationContext context: Context,
        locationService: LocationService
    ): SensorService {
        return SensorService(context, locationService)
    }
    
    @Provides
    @Singleton
    fun provideSignalKTransmitter(
        @ApplicationContext context: Context,
        authenticationService: AuthenticationService
    ): SignalKTransmitter {
        val transmitter = SignalKTransmitter(authenticationService)
        transmitter.setContext(context)
        return transmitter
    }
}
