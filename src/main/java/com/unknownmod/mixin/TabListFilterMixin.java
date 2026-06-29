// REMOVED: TabListFilterMixin cleared all entries from ClientboundPlayerInfoUpdatePacket,
// which conflicted with PacketS2CFilterMixin (entries were empty before anonymization could run)
// and broke latency/gamemode updates in the tab list.
// PacketS2CFilterMixin now handles all tab list filtering by anonymizing profiles instead.
