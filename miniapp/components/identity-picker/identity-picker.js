Component({
  properties: {
    visible: { type: Boolean, value: false },
    identities: { type: Array, value: [] },
    title: { type: String, value: '切换身份' },
    subtitle: { type: String, value: '点选小区身份；住户多套房可分别进入' }
  },
  methods: {
    noop() {},
    onClose() {
      this.triggerEvent('close')
    },
    onPick(e) {
      const item = e.currentTarget.dataset.item
      if (!item || item.active) {
        this.triggerEvent('close')
        return
      }
      this.triggerEvent('select', { item })
    }
  }
})
