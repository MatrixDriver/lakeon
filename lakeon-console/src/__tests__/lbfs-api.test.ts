import { describe, it, expect, vi, beforeEach } from 'vitest'
import client from '../api/client'
import { createLBFSFolder, listLBFSFolders, listLBFSProcessingJobs } from '../api/lbfs'

vi.mock('../api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}))

describe('LakebaseFS folder API', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists folder profiles from the LakebaseFS registry', async () => {
    vi.mocked(client.get).mockResolvedValue({
      data: {
        folders: [
          {
            id: 'fld_1',
            display_name: 'warehouse',
            directory_kind: 'data-dir',
            storage_policy: 'object-first',
            processing_profile: 'dataset',
            status: 'active',
          },
        ],
      },
    })

    const result = await listLBFSFolders()

    expect(client.get).toHaveBeenCalledWith('/lbfs/folders')
    expect(result.data.folders[0].display_name).toBe('warehouse')
  })

  it('creates a folder profile with user-declared directory kind', async () => {
    vi.mocked(client.post).mockResolvedValue({
      data: {
        id: 'fld_2',
        display_name: 'notes',
        directory_kind: 'files',
        storage_policy: 'auto',
        processing_profile: 'none',
        status: 'active',
      },
    })

    const result = await createLBFSFolder({
      display_name: 'notes',
      directory_kind: 'files',
    })

    expect(client.post).toHaveBeenCalledWith('/lbfs/folders', {
      display_name: 'notes',
      directory_kind: 'files',
    })
    expect(result.data.processing_profile).toBe('none')
  })

  it('creates an opencode home folder profile', async () => {
    vi.mocked(client.post).mockResolvedValue({
      data: {
        id: 'fld_opencode',
        display_name: 'opencode-runtime',
        directory_kind: 'opencode-home',
        storage_policy: 'auto',
        processing_profile: 'agent-home',
        status: 'active',
      },
    })

    const result = await createLBFSFolder({
      display_name: 'opencode-runtime',
      directory_kind: 'opencode-home',
    })

    expect(client.post).toHaveBeenCalledWith('/lbfs/folders', {
      display_name: 'opencode-runtime',
      directory_kind: 'opencode-home',
    })
    expect(result.data.directory_kind).toBe('opencode-home')
    expect(result.data.processing_profile).toBe('agent-home')
  })

  it('lists processing jobs for a folder', async () => {
    vi.mocked(client.get).mockResolvedValue({
      data: {
        jobs: [
          {
            id: 'job_1',
            folder_id: 'fld_1',
            source_path: '/datasets/orders.csv',
            profile: 'dataset',
            status: 'running',
            attempts: 1,
            last_error: null,
          },
        ],
      },
    })

    const result = await listLBFSProcessingJobs('fld_1')

    expect(client.get).toHaveBeenCalledWith('/lbfs/folders/fld_1/jobs')
    expect(result.data.jobs[0].source_path).toBe('/datasets/orders.csv')
    expect(result.data.jobs[0].status).toBe('running')
  })
})
