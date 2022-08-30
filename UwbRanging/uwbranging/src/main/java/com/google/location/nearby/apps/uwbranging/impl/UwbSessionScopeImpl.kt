package com.google.location.nearby.apps.uwbranging.impl

import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbDevice
import com.google.location.nearby.apps.uwbranging.EndpointEvents
import com.google.location.nearby.apps.uwbranging.UwbEndpoint
import com.google.location.nearby.apps.uwbranging.UwbSessionScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

internal class UwbSessionScopeImpl(
  private val localEndpoint: UwbEndpoint,
  private val connector: NearbyConnector,
) : UwbSessionScope {

  private val localAddresses = mutableSetOf<UwbAddress>()

  private val remoteDeviceMap = mutableMapOf<UwbAddress, UwbEndpoint>()

  private val activeJobs = mutableMapOf<UwbEndpoint, Job>()

  override fun prepareSession() = channelFlow {
    val job = launch {
      connector.start().collect { event ->
        when (event) {
          is UwbOobEvent.UwbEndpointFound -> {
            val rangingEvents = processEndpointFound(event)
            activeJobs[event.endpoint] = launch { rangingEvents.collect { sendResult(it) } }
          }
          is UwbOobEvent.UwbEndpointLost -> processEndpointLost(event.endpoint)
          is UwbOobEvent.MessageReceived ->
            trySend(EndpointEvents.EndpointMessage(event.endpoint, event.message))
        }
      }
    }
    awaitClose {
      job.cancel()
      remoteDeviceMap.clear()
    }
  }

  override fun sendMessage(endpoint: UwbEndpoint, message: ByteArray) {
    connector.sendMessage(endpoint, message)
  }

  private fun ProducerScope<EndpointEvents>.processEndpointLost(endpoint: UwbEndpoint) {
    trySend(EndpointEvents.EndpointLost(endpoint))
    activeJobs[endpoint]?.cancel()
  }

  private fun ProducerScope<EndpointEvents>.processEndpointFound(
    event: UwbOobEvent.UwbEndpointFound,
  ): Flow<RangingResult> {
    remoteDeviceMap[event.endpointAddress] = event.endpoint
    localAddresses.add(event.sessionScope.localAddress)
    val rangingParameters =
      RangingParameters(
        event.configId,
        event.sessionId,
        event.sessionKeyInfo,
        event.complexChannel,
        listOf(UwbDevice(event.endpointAddress)),
        RangingParameters.RANGING_UPDATE_RATE_FREQUENT
      )
    trySend(EndpointEvents.EndpointFound(event.endpoint))
    return event.sessionScope.prepareSession(rangingParameters)
  }

  private fun ProducerScope<EndpointEvents>.sendResult(result: RangingResult) {
    val endpoint =
      if (localAddresses.contains(result.device.address)) localEndpoint
      else remoteDeviceMap[result.device.address] ?: return
    when (result) {
      is RangingResult.RangingResultPosition ->
        trySend(EndpointEvents.PositionUpdated(endpoint, result.position))
      is RangingResult.RangingResultPeerDisconnected ->
        trySend(EndpointEvents.UwbDisconnected(endpoint))
    }
  }
}
