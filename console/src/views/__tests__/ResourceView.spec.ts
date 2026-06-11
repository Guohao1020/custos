import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../../api/client', () => ({
  createClient: () => ({
    get: vi.fn().mockResolvedValue({ data: [] }),
    post: vi.fn().mockResolvedValue({ data: {} }),
    delete: vi.fn().mockResolvedValue({ data: {} }),
  }),
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))

import ResourceView from '../ResourceView.vue'

describe('ResourceView 密码不回显', () => {
  it('注册后 adminPassword 被清空', async () => {
    const wrapper = mount(ResourceView, {
      global: {
        stubs: {
          'el-table': true, 'el-table-column': true, 'el-card': true, 'el-input': true,
          'el-button': { template: '<button @click="$emit(\'click\')"><slot/></button>' },
        },
      },
    })
    const vm: any = wrapper.vm
    vm.form.adminPassword = 'super-secret'
    await vm.register()
    expect(vm.form.adminPassword).toBe('')   // 提交后立即清空
  })
})
